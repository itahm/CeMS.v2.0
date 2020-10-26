package com.itahm.util;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.itahm.json.JSONObject;

public class Syslog {
	private static final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss", Locale.US);
	private InetSocketAddress isa;
	
	public Syslog() throws IllegalArgumentException {
	}
	
	public Syslog(String server) throws IllegalArgumentException {
		set(server);
	}
	
	synchronized public void set(String server) throws IllegalArgumentException {
		if (server == null) {
			this.isa = null;
		} else {
			String [] address = server.split(":");
			
			if (address.length == 2) {
				isa = new InetSocketAddress(address[0], Integer.valueOf(address[1]));
				
				if (isa.isUnresolved()) {
					throw new IllegalArgumentException();
				} else {
					try {
						send(createMsg("Syslog Connected", 23, 6));
					} catch (IOException ioe) {
						throw new IllegalArgumentException();		
					}
				}
			} else {
				throw new IllegalArgumentException();
			}
		}
	}
	
	private byte [] createMsg(String msg, int facility, int severity) throws UnsupportedEncodingException {
		return String.format("<%d>%s CeMS %s"
			, 0xff & ((facility << 3) | (0x07 & severity))
			,sdf.format(new Date())
			, msg).getBytes(StandardCharsets.US_ASCII.name());
	}
	
	public void send(String ...args) {
	}
	
	public void send(JSONObject msg) {
		try {
			send(createMsg(msg.getString("message"), 23, 6));
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	synchronized public void send(byte [] msg) throws IOException {
		if (this.isa != null) {
			DatagramChannel dc = DatagramChannel.open(StandardProtocolFamily.INET);
			
			dc.send(ByteBuffer.wrap(msg), this.isa);
			
			dc.close();
		}
	}
}
