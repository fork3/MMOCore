package com.l2jserver.mmocore;

import java.net.InetAddress;
import java.nio.ByteOrder;

import f3.commons.nif.IncomePacketListener;
import f3.commons.nif.NetworkServerConfiguration;
import f3.commons.nif.PacketSerializer;
import lombok.Data;

/**
 * @author n3k0nation
 *
 */
@Data
public class MMOConfiguration implements NetworkServerConfiguration {
	
	private int readBufferSize;
	private int writeBufferSize;
	private int maxSendPerPass;
	private long selectorSleepTime;
	private long interestDelayTime;
	private int packetHeaderSize;
	private int maxPacketSize;
	private int secondaryBufferCount;
	private long authTimeout;
	private long closeTimeout;
	private int backlog;
	private ByteOrder byteOrder;
	private InetAddress bindAddress;
	private int bindPort;
	private IncomePacketListener incomePacketListener;
	private PacketSerializer packetSerializer;

}
