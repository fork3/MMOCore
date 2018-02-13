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
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritableByteChannel;
import java.util.Collection;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import f3.commons.nif.OutcomeNetworkPacket;

/**
 * @author KenM
 * @param <T>
 */
public class MMOConnection
{
	private final SelectorThread selectorThread;
	
	private final Socket socket;
	
	private final InetAddress address;
	
	private final ReadableByteChannel readableByteChannel;
	
	private final WritableByteChannel writableByteChannel;
	
	private final int port;
	
	private final NioNetStackList<OutcomeNetworkPacket> sendQueue = new NioNetStackList<>();
	
	private final SelectionKey selectionKey;
	
	private ByteBuffer readBuffer;
	
	private ByteBuffer primaryWriteBuffer;
	
	private ByteBuffer secondaryWriteBuffer;
	
	private volatile boolean pendingClose;
	
	private MMOClient client;
	
	private Lock lock = new ReentrantLock();
	
	public MMOConnection(final SelectorThread selectorThread, final Socket socket, final SelectionKey key)
	{
		this.selectorThread = selectorThread;
		this.socket = socket;
		address = socket.getInetAddress();
		readableByteChannel = socket.getChannel();
		writableByteChannel = socket.getChannel();
		port = socket.getPort();
		selectionKey = key;
		
		try
		{
			socket.setTcpNoDelay(true);
		}
		catch (SocketException e)
		{
			e.printStackTrace();
		}
	}
	
	final void setClient(final MMOClient client)
	{
		this.client = client;
	}
	
	public final MMOClient getClient()
	{
		return client;
	}
	
	public final void sendPacket(final OutcomeNetworkPacket...sp) {
		if (pendingClose) {
			return;
		}
		
		lock();
		try {
			for(int i = 0; i < sp.length; i++) {
				final OutcomeNetworkPacket packet = sp[i];
				packet.setClient(client);
				sendQueue.addLast(packet);
			}
		} finally {
			unlock();
		}
		
		if (!sendQueue.isEmpty()) {
			try {
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
			} catch (CancelledKeyException e) {
				// ignore
			}
		}
	}
	
	public final void sendPacket(Collection<OutcomeNetworkPacket> sp) {
		if (pendingClose) {
			return;
		}
		
		lock();
		try {
			for(OutcomeNetworkPacket packet : sp) {
				packet.setClient(client);
				sendQueue.addLast(packet);
			}
		} finally {
			unlock();
		}
		
		if (!sendQueue.isEmpty()) {
			try {
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
			} catch (CancelledKeyException e) {
				// ignore
			}
		}
	}
	
	final SelectionKey getSelectionKey()
	{
		return selectionKey;
	}
	
	public final InetAddress getInetAddress()
	{
		return address;
	}
	
	public final int getPort()
	{
		return port;
	}
	
	final void closeSocket() throws IOException
	{
		socket.close();
	}
	
	final int read(final ByteBuffer buf) throws IOException
	{
		return readableByteChannel.read(buf);
	}
	
	final int write(final ByteBuffer buf) throws IOException
	{
		return writableByteChannel.write(buf);
	}
	
	final void createWriteBuffer(final ByteBuffer buf)
	{
		if (primaryWriteBuffer == null)
		{
			primaryWriteBuffer = selectorThread.getPooledBuffer();
			primaryWriteBuffer.put(buf);
		}
		else
		{
			final ByteBuffer temp = selectorThread.getPooledBuffer();
			temp.put(buf);
			
			final int remaining = temp.remaining();
			primaryWriteBuffer.flip();
			final int limit = primaryWriteBuffer.limit();
			
			if (remaining >= primaryWriteBuffer.remaining())
			{
				temp.put(primaryWriteBuffer);
				selectorThread.recycleBuffer(primaryWriteBuffer);
				primaryWriteBuffer = temp;
			}
			else
			{
				primaryWriteBuffer.limit(remaining);
				temp.put(primaryWriteBuffer);
				primaryWriteBuffer.limit(limit);
				primaryWriteBuffer.compact();
				secondaryWriteBuffer = primaryWriteBuffer;
				primaryWriteBuffer = temp;
			}
		}
	}
	
	final boolean hasPendingWriteBuffer()
	{
		return primaryWriteBuffer != null;
	}
	
	final void movePendingWriteBufferTo(final ByteBuffer dest)
	{
		primaryWriteBuffer.flip();
		dest.put(primaryWriteBuffer);
		selectorThread.recycleBuffer(primaryWriteBuffer);
		primaryWriteBuffer = secondaryWriteBuffer;
		secondaryWriteBuffer = null;
	}
	
	final void setReadBuffer(final ByteBuffer buf)
	{
		readBuffer = buf;
	}
	
	final ByteBuffer getReadBuffer()
	{
		return readBuffer;
	}
	
	public final boolean isClosed()
	{
		return pendingClose;
	}
	
	final NioNetStackList<OutcomeNetworkPacket> getSendQueue()
	{
		return sendQueue;
	}
	
	public final void close(final OutcomeNetworkPacket... closeList)
	{
		if (pendingClose)
		{
			return;
		}
		
		lock();
		try {
			if (!pendingClose)
			{
				pendingClose = true;
				sendQueue.clear();
				for(int i = 0; i < closeList.length; i++) {
					final OutcomeNetworkPacket packet = closeList[i];
					packet.setClient(client);
					sendQueue.addLast(packet);
				}
			}
		} finally {
			unlock();
		}
		
		try
		{
			selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
		}
		catch (CancelledKeyException e)
		{
			// ignore
		}
		
		// _closePacket = sp;
		selectorThread.closeConnection(this);
	}
	
	public void close() {
		if (pendingClose) {
			return;
		}
		
		selectorThread.closeConnection(this);
	}
	
	final void releaseBuffers()
	{
		if (primaryWriteBuffer != null)
		{
			selectorThread.recycleBuffer(primaryWriteBuffer);
			primaryWriteBuffer = null;
			
			if (secondaryWriteBuffer != null)
			{
				selectorThread.recycleBuffer(secondaryWriteBuffer);
				secondaryWriteBuffer = null;
			}
		}
		
		if (readBuffer != null)
		{
			selectorThread.recycleBuffer(readBuffer);
			readBuffer = null;
		}
	}
	
	void lock() {
		lock.lock();
	}
	
	boolean tryLock() {
		return lock.tryLock();
	}
	
	void unlock() {
		lock.unlock();
	}
}
