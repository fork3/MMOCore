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

import java.util.Collection;

import f3.commons.nif.NetworkCipher;
import f3.commons.nif.NetworkClient;
import f3.commons.nif.OutcomeNetworkPacket;
import f3.commons.nif.listener.NetworkClientListenerList;
import lombok.Getter;

/**
 * @author KenM
 * @param <T>
 */
public class MMOClient implements NetworkClient {
	private final NetworkClientListenerList listeners = new NetworkClientListenerList(this);
	@Getter private final MMOConnection con;
	private boolean isAuthed;
	private NetworkCipher cipher;
	
	public MMOClient(final MMOConnection con) {
		this.con = con;
	}

	@Override
	public int getRuntimeId() {
		return 0;
	}

	@Override
	public boolean isAuthed() {
		return isAuthed;
	}

	@Override
	public void setAuthed(boolean isAuthed) {
		this.isAuthed = isAuthed;
	}

	@Override
	public void closeNow() {
		con.close();
	}

	@Override
	public void closeLater() {
		con.close();
	}

	@Override
	public boolean isConnected() {
		return !con.isClosed();
	}

	@Override
	public NetworkCipher getCipher() {
		return cipher;
	}

	@Override
	public void setCipher(NetworkCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public void sendPacket(OutcomeNetworkPacket... packets) {
		con.sendPacket(packets);
	}

	@Override
	public void sendPacket(Collection<OutcomeNetworkPacket> packets) {
		con.sendPacket(packets);
	}
	
	@Override
	public NetworkClientListenerList getListeners() {
		return listeners;
	}
}
