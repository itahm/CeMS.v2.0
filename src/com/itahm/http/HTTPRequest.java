package com.itahm.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

public class HTTPRequest implements Reques {

	private final SocketAddress peer;
	private final Map<String, String> header;
	private final Map<String, Object> attribute = new HashMap<>();
	private final byte [] body;
	private final String method;
	private final String uri;
	private final String version;
	private final String sessionID;
	private Session session;
	private String queryString;
	
	public HTTPRequest(SocketAddress peer, String startLine, Map<String, String> header, byte [] body) throws IOException {
		this.peer = peer;
		this.header = header;
		this.body = body;
		
		String [] token = startLine.split(" ");
		if (token.length != 3) {
			throw new IOException("malformed http request");
		}
		
		this.method = token[0];
		
		String uri = token[1];
		int i = uri.indexOf("?");
		
		if (i == -1) {
			this.uri = uri;
		}
		else {
			this.uri = uri.substring(0, i);
			
			this.queryString = uri.substring(i);
		}
		
		this.version = token[2];
		this.sessionID = getHeader(Connection.Header.SESSION.toString());
	}
	
	public byte [] read() {
		return this.body;
	}
	
	public Object getAttribute(String name) {
		return this.attribute.get(name);
	}
	
	public String getRemoteAddr() {
		return ((InetSocketAddress)this.peer).getAddress().getHostAddress();
	}
	
	public Session getSession() {
		return getSession(true);
	}
	
	@Override
	public Session getSession(boolean create) {
		if (this.session == null) {
			if (this.sessionID != null) {
				this.session = Session.find(this.sessionID);
			}
			
			if (this.session != null) {
				this.session.update();
			}
			else if (create) {
				this.session = new Session();
			}
		}
		
		return this.session;
	}
	
	public String getRequestURI() {
		return this.uri;
	}
	
	public String getVersion() {
		return this.version;
	}
	
	public String getMethod() {
		return this.method;
	}
	
	public String getRequestedSessionId() {
		return this.sessionID;
	}
	
	public String getQueryString() {
		return this.queryString;
	}
	
	public String getHeader(String name) {
		return this.header.get(name.toLowerCase());
	}
	
	public void setAttribute(String name, Object o) {
		this.attribute.put(name, o);
	}

}
