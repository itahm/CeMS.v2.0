package com.itahm.servlet;

import javax.servlet.http.HttpServletRequest;

import com.itahm.http.Connection;
import com.itahm.http.Session;

public class ServletRequest implements com.itahm.http.Reques{
	private final HttpServletRequest request;
	private Session session;
	private final String sessionID;
	
	public ServletRequest(HttpServletRequest request) {
		this.request = request;
		
		sessionID = this.request.getHeader(Connection.Header.SESSION.toString());
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
}
