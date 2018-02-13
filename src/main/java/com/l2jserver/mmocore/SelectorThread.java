/* This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package com.l2jserver.mmocore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import f3.commons.nif.IncomeNetworkPacket;
import f3.commons.nif.IncomePacketListener;
import f3.commons.nif.NetworkCipher;
import f3.commons.nif.NetworkServer;
import f3.commons.nif.NetworkServerConfiguration;
import f3.commons.nif.OutcomeNetworkPacket;
import f3.commons.nif.PacketSerializer;

/**
 * Parts of design based on network core from WoodenGil
 * @param <T>
 * @author KenM, Zoey76
 */
public final class SelectorThread extends Thread implements NetworkServer
{
	// Selector
	private final Selector selector;
	// Configurations
	private final ByteOrder BYTE_ORDER;
	private final int HEADER_SIZE;
	private final int HELPER_BUFFER_SIZE;
	private final int HELPER_BUFFER_COUNT;
	private final int MAX_SEND_PER_PASS;
	private final long SLEEP_TIME;
	private final long CLOSE_TIMEOUT;
	private final InetAddress BIND_ADDRESS;
	private final int BIND_PORT;
	
	// Main Buffers
	private final ByteBuffer DIRECT_WRITE_BUFFER;
	private final ByteBuffer WRITE_BUFFER;
	private final ByteBuffer READ_BUFFER;
	// ByteBuffers General Purpose Pool
	private final Queue<ByteBuffer> bufferPool;
	
	private final Map<MMOConnection, Long> pendingClose = new ConcurrentHashMap<>();
	
	private final IncomePacketListener inPacketListener;
	private final PacketSerializer packetSerializer;
	
	private volatile boolean shutdown;
	
	public SelectorThread(NetworkServerConfiguration cfg) throws IOException
	{
		super.setName("SelectorThread-" + super.getId());
		
		BYTE_ORDER = cfg.getByteOrder();
		HEADER_SIZE = cfg.getPacketHeaderSize();
		
		BIND_ADDRESS = cfg.getBindAddress();
		BIND_PORT = cfg.getBindPort();
		
		HELPER_BUFFER_SIZE = Math.max(cfg.getReadBufferSize(), cfg.getWriteBufferSize());
		HELPER_BUFFER_COUNT = cfg.getSecondaryBufferCount();
		MAX_SEND_PER_PASS = cfg.getMaxSendPerPass();
		SLEEP_TIME = cfg.getSelectorSleepTime();
		CLOSE_TIMEOUT = cfg.getCloseTimeout();
		
		DIRECT_WRITE_BUFFER = ByteBuffer.allocateDirect(cfg.getWriteBufferSize()).order(BYTE_ORDER);
		WRITE_BUFFER = ByteBuffer.wrap(new byte[cfg.getWriteBufferSize()]).order(BYTE_ORDER);
		READ_BUFFER = ByteBuffer.wrap(new byte[cfg.getReadBufferSize()]).order(BYTE_ORDER);
		
		bufferPool = new ConcurrentLinkedQueue<>();
		
		for (int i = 0; i < HELPER_BUFFER_COUNT; i++)
		{
			bufferPool.add(ByteBuffer.wrap(new byte[HELPER_BUFFER_SIZE]).order(BYTE_ORDER));
		}
		
		inPacketListener = cfg.getIncomePacketListener();
		packetSerializer = cfg.getPacketSerializer();
		
		selector = Selector.open();
	}
	
	public final void openServerSocket(InetAddress address, int tcpPort) throws UncheckedIOException {
		try {
			ServerSocketChannel selectable = ServerSocketChannel.open();
			selectable.configureBlocking(false);
			
			ServerSocket ss = selectable.socket();
			
			if (address == null)
			{
				ss.bind(new InetSocketAddress(tcpPort));
			}
			else
			{
				ss.bind(new InetSocketAddress(address, tcpPort));
			}
			
			selectable.register(selector, SelectionKey.OP_ACCEPT);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	@Override
	public void startServer() {
		openServerSocket(BIND_ADDRESS, BIND_PORT);
	}
	
	@Override
	public void stopServer() {
		shutdown();
	}
	
	final ByteBuffer getPooledBuffer()
	{
		if (bufferPool.isEmpty())
		{
			return ByteBuffer.wrap(new byte[HELPER_BUFFER_SIZE]).order(BYTE_ORDER);
		}
		
		return bufferPool.remove();
	}
	
	final void recycleBuffer(ByteBuffer buf)
	{
		if (bufferPool.size() < HELPER_BUFFER_COUNT)
		{
			buf.clear();
			bufferPool.add(buf);
		}
	}
	
	@Override
	public final void run()
	{
		while (!shutdown)
		{
			int selectedKeysCount = 0;
			try
			{
				selectedKeysCount = selector.selectNow();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			
			if (selectedKeysCount > 0)
			{
				Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
				
				while (selectedKeys.hasNext())
				{
					SelectionKey key = selectedKeys.next();
					selectedKeys.remove();
					
					MMOConnection con = (MMOConnection) key.attachment();
					
					switch (key.readyOps())
					{
						case SelectionKey.OP_CONNECT:
							finishConnection(key, con);
							break;
						case SelectionKey.OP_ACCEPT:
							acceptConnection(key, con);
							break;
						case SelectionKey.OP_READ:
							readPacket(key, con);
							break;
						case SelectionKey.OP_WRITE:
							writePacket(key, con);
							break;
						case SelectionKey.OP_READ | SelectionKey.OP_WRITE:
							writePacket(key, con);
							if (key.isValid())
							{
								readPacket(key, con);
							}
							break;
					}
				}
			}
			
			final long currentTime = System.currentTimeMillis() - CLOSE_TIMEOUT;
			for(Map.Entry<MMOConnection, Long> entry : pendingClose.entrySet()) {
				final MMOConnection con = entry.getKey();
				final Long putTime = entry.getValue();
				
				if(putTime < currentTime) {
					try {
						if(!writeClosePacket(con)) {
							continue;
						}
						pendingClose.remove(con);
						closeConnectionImpl(con.getSelectionKey(), con, false);
					} catch(Exception e) {
						e.printStackTrace();
					}
				}
			}
			
			try {
				Thread.sleep(SLEEP_TIME);
			} catch (InterruptedException e) {
				break;
			}
		}
		closeSelectorThread();
	}
	
	private final void finishConnection(SelectionKey key, MMOConnection con)
	{
		try
		{
			((SocketChannel) key.channel()).finishConnect();
		}
		catch (IOException e)
		{
			closeConnectionImpl(key, con, true);
		}
		
		// key might have been invalidated on finishConnect()
		if (key.isValid())
		{
			key.interestOps(key.interestOps() | SelectionKey.OP_READ);
			key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
		}
	}
	
	private final void acceptConnection(SelectionKey key, MMOConnection con)
	{
		ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
		SocketChannel sc;
		
		try
		{
			while ((sc = ssc.accept()) != null)
			{
				sc.configureBlocking(false);
				SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ);
				con = new MMOConnection(this, sc.socket(), clientKey);
				con.setClient(new MMOClient(con)); //XXX
				clientKey.attach(con);
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private final void readPacket(SelectionKey key, MMOConnection con) {
		if (con.isClosed()) {
			return;
		}

		ByteBuffer buf = con.getReadBuffer();
		if (buf == null) {
			buf = READ_BUFFER;
		}

		if (buf.position() == buf.limit()) {
			closeConnectionImpl(key, con, true);
			return;
		}

		final int result;
		try {
			result = con.read(buf);
		} catch (IOException e) {
			closeConnectionImpl(key, con, true);
			return;
		}

		if (result > 0) {
			buf.flip();

			final MMOClient client = con.getClient();

			for (;;) {
				if (!tryReadPacket(key, client, buf, con)) {
					return;
				}
			}
		} else if (result == 0 || result == -1) {
			closeConnectionImpl(key, con, false);
		}
	}
	
	private final boolean tryReadPacket(SelectionKey key, MMOClient client, ByteBuffer buf, MMOConnection con)
	{
		switch (buf.remaining())
		{
			case 0:
				// buffer is full
				// nothing to read
				return false;
			case 1:
				// we don`t have enough data for header so we need to read
				key.interestOps(key.interestOps() | SelectionKey.OP_READ);
				
				// did we use the READ_BUFFER ?
				if (buf == READ_BUFFER)
				{
					// move the pending byte to the connections READ_BUFFER
					allocateReadBuffer(con);
				}
				else
				{
					// move the first byte to the beginning :)
					buf.compact();
				}
				return false;
			default:
				// data size excluding header size :>
				final int dataPending = (buf.getShort() & 0xFFFF) - HEADER_SIZE;
				
				// do we got enough bytes for the packet?
				if (dataPending <= buf.remaining())
				{
					// avoid parsing dummy packets (packets without body)
					if (dataPending > 0)
					{
						final int pos = buf.position();
						parseClientPacket(pos, buf, dataPending, client);
						buf.position(pos + dataPending);
					}
					
					// if we are done with this buffer
					if (!buf.hasRemaining())
					{
						if (buf != READ_BUFFER)
						{
							con.setReadBuffer(null);
							recycleBuffer(buf);
						}
						else
						{
							READ_BUFFER.clear();
						}
						return false;
					}
					return true;
				}
				
				// we don`t have enough bytes for the dataPacket so we need
				// to read
				key.interestOps(key.interestOps() | SelectionKey.OP_READ);
				
				// did we use the READ_BUFFER ?
				if (buf == READ_BUFFER)
				{
					// move it`s position
					buf.position(buf.position() - HEADER_SIZE);
					// move the pending byte to the connections READ_BUFFER
					allocateReadBuffer(con);
				}
				else
				{
					buf.position(buf.position() - HEADER_SIZE);
					buf.compact();
				}
				return false;
		}
	}
	
	private final void allocateReadBuffer(MMOConnection con)
	{
		con.setReadBuffer(getPooledBuffer().put(READ_BUFFER));
		READ_BUFFER.clear();
	}
	
	private final void parseClientPacket(int pos, ByteBuffer buf, int dataSize, MMOClient client)
	{
		final NetworkCipher cipher = client.getCipher();
		boolean successDecrypt;
		if(cipher != null) {
			successDecrypt = cipher.decrypt(buf, dataSize);
		} else {
			successDecrypt = true;
		}
		
		if (successDecrypt && buf.hasRemaining())
		{
			// apply limit
			final int limit = buf.limit();
			buf.limit(pos + dataSize);
			final IncomeNetworkPacket cp = packetSerializer.deserialize(client, buf);
			inPacketListener.onPacketIncome(cp);
			buf.limit(limit);
		}
	}
	
	private boolean writeClosePacket(MMOConnection con) {
		if(!con.tryLock()) { //client is busy. try write on next pass
			return false;
		}
		
		try {
			if (con.getSendQueue().isEmpty())
			{
				return true;
			}
			
			OutcomeNetworkPacket sp;
			while ((sp = con.getSendQueue().removeFirst()) != null)
			{
				WRITE_BUFFER.clear();
				
				putPacketIntoWriteBuffer(con.getClient(), sp);
				
				WRITE_BUFFER.flip();
				
				try
				{
					con.write(WRITE_BUFFER);
				}
				catch (IOException e)
				{
					// error handling goes on the if bellow
				}
			}
		} finally {
			con.unlock();
		}
		
		return true;
	}
	
	protected final void writePacket(SelectionKey key, MMOConnection con)
	{
		if(!con.tryLock()) {
			return;
		}
		
		if (!prepareWriteBuffer(con))
		{
			key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
			return;
		}
		
		DIRECT_WRITE_BUFFER.flip();
		
		final int size = DIRECT_WRITE_BUFFER.remaining();
		
		int result = -1;
		
		try
		{
			result = con.write(DIRECT_WRITE_BUFFER);
		}
		catch (IOException e)
		{
			// error handling goes on the if bellow
		}
		
		// check if no error happened
		if (result >= 0)
		{
			// check if we written everything
			if (result == size)
			{
				// complete write
				con.lock();
				try {
					if (con.getSendQueue().isEmpty() && !con.hasPendingWriteBuffer())
					{
						key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
					}
				} finally {
					con.unlock();
				}
			}
			else
			{
				// incomplete write
				con.createWriteBuffer(DIRECT_WRITE_BUFFER);
			}
		}
		else
		{
			closeConnectionImpl(key, con, true);
		}
	}
	
	private final boolean prepareWriteBuffer(MMOConnection con)
	{
		boolean hasPending = false;
		DIRECT_WRITE_BUFFER.clear();
		
		// if there is pending content add it
		if (con.hasPendingWriteBuffer())
		{
			con.movePendingWriteBufferTo(DIRECT_WRITE_BUFFER);
			hasPending = true;
		}
		
		if ((DIRECT_WRITE_BUFFER.remaining() > 1) && !con.hasPendingWriteBuffer())
		{
			final NioNetStackList<OutcomeNetworkPacket> sendQueue = con.getSendQueue();
			final MMOClient client = con.getClient();
			OutcomeNetworkPacket sp;
			
			for (int i = 0; i < MAX_SEND_PER_PASS; i++)
			{
				con.lock();
				try {
					if (sendQueue.isEmpty())
					{
						sp = null;
					}
					else
					{
						sp = sendQueue.removeFirst();
					}
				} finally {
					con.unlock();
				}
				
				if (sp == null)
				{
					break;
				}
				
				hasPending = true;
				
				// put into WriteBuffer
				putPacketIntoWriteBuffer(client, sp);
				
				WRITE_BUFFER.flip();
				
				if (DIRECT_WRITE_BUFFER.remaining() >= WRITE_BUFFER.limit())
				{
					DIRECT_WRITE_BUFFER.put(WRITE_BUFFER);
				}
				else
				{
					con.createWriteBuffer(WRITE_BUFFER);
					break;
				}
			}
		}
		return hasPending;
	}
	
	private final void putPacketIntoWriteBuffer(MMOClient client, OutcomeNetworkPacket sp)
	{
		WRITE_BUFFER.clear();
		
		// reserve space for the size
		final int headerPos = WRITE_BUFFER.position();
		final int dataPos = headerPos + HEADER_SIZE;
		WRITE_BUFFER.position(dataPos);
		
		// set the write buffer
		sp.setClient(client);
		packetSerializer.serialize(client, sp, WRITE_BUFFER);
		
		// size (inclusive header)
		int dataSize = WRITE_BUFFER.position() - dataPos;
		WRITE_BUFFER.position(dataPos);
		
		final NetworkCipher cipher = client.getCipher();
		if(cipher != null) {
			cipher.encrypt(WRITE_BUFFER, dataSize);
		}
		
		// recalculate size after encryption
		dataSize = WRITE_BUFFER.position() - dataPos;
		
		WRITE_BUFFER.position(headerPos);
		// write header
		WRITE_BUFFER.putShort((short) (dataSize + HEADER_SIZE));
		WRITE_BUFFER.position(dataPos + dataSize);
	}
	
	final void closeConnection(final MMOConnection con) {
		pendingClose.put(con, System.currentTimeMillis());
	}
	
	private final void closeConnectionImpl(SelectionKey key, MMOConnection con, boolean isDisconnection) {
		final MMOClient client = con.getClient();
		if(client != null) {
			if(isDisconnection) {
				client.getListeners().onDisconnect();
			} else {
				client.getListeners().onCloseConnection();
			}
		}
		
		try {
			// close socket and the SocketChannel
			con.closeSocket();
		} catch (IOException e) {
			// ignore, we are closing anyway
		} finally {
			con.releaseBuffers();
			// clear attachment
			key.attach(null);
			// cancel key
			key.cancel();
		}
	}
	
	public final void shutdown()
	{
		shutdown = true;
	}
	
	protected void closeSelectorThread()
	{
		for (final SelectionKey key : selector.keys())
		{
			try
			{
				key.channel().close();
			}
			catch (IOException e)
			{
				// ignore
			}
		}
		
		try
		{
			selector.close();
		}
		catch (IOException e)
		{
			// Ignore
		}
	}
}
