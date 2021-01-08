package com.itahm.service;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;


import javax.servlet.http.HttpServletResponse;

import com.itahm.http.Reques;
import com.itahm.http.Response;
import com.itahm.http.Session;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.Bean.Event;
import com.itahm.nms.command.Command;
import com.itahm.nms.Commander;
import com.itahm.nms.H2Agent;
import com.itahm.smtp.SMTP;
import com.itahm.util.Listener;

public class NMS implements Listener {

	/*********************************************************/
	public final static long EXPIRE = -1L;
	public final static long LIMIT = 0L;
	public final static String VERSION = "CeMS v2.0";
	/*********************************************************/
	
	private final static int SESS_TIMEOUT = 3600;
	
	private Commander agent;
	public final static SMTP smtpServer = new SMTP();
	private final Command command;
	private String event = null;
	
	
	public NMS(Path path) throws Exception {
		agent = new H2Agent(this, path);
		
		try {
			
			JSONObject config = this.agent.getConfig();
			
			if (config.has("smtpEnable") && config.getBoolean("smtpEnable")) {
				smtpServer.enable(config.getString("smtpServer"),
					config.getString("smtpProtocol"),
					config.getString("smtpUser"),
					config.getString("smtpPassword"));
			}
			
			this.agent.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		command = new Command(agent, path);
	}
	
	public void close() {
		try {
			this.command.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		try {
			this.agent.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public void service(Reques request, Response response, JSONObject data) {
		Session session = request.getSession(false);
		
		try {
			if (session == null) {
				JSONObject user = this.agent.signIn(data.getString("id"), data.getString("password"));
			
				if (user == null) {
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				} else {
					session = request.getSession(true);
					
					session.setAttribute("account", user);
					
					session.setMaxInactiveInterval(SESS_TIMEOUT);
					
					response.setHeader("Set-Session", session.id);
					
					response.write(user.toString());
				}
			} else {
				switch (data.getString("command").toUpperCase()) {
				case "LISTEN":
					JSONObject event = null;
					
					if (data.has("eventID")) {
						try {
							event = this.agent.getEvent(data.getLong("eventID"));
						} catch (SQLException sqle) {
							response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							
							sqle.printStackTrace();
							
							break;
						}
					}
					
					if (event == null) {
						synchronized(this) {
							try {
								wait();
							} catch (InterruptedException ie) {
							}
							
							response.write(this.event.toString());
						}
					}
					else {
						response.write(event.toString());
					}
					
					break;
				case "SIGNIN":
					response.write((JSONObject)session.getAttribute("account"));
					
					break;
				case "SIGNOUT":
					session.invalidate();
					
					break;
				default:
					
					this.command.execute(request, response, data);
				}
			}
		} catch (JSONException e) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			
			response.write(new JSONObject().
				put("error", e.getMessage()));
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			
			response.write(new JSONObject().
				put("error", e.getMessage()));
			
			e.printStackTrace();
		}
	}
		
	@Override
	public void onEvent(Object caller, Object ...args) {
		if (caller instanceof Commander) {
			if (args[0] instanceof JSONObject) {
				JSONObject event = (JSONObject)args[0];
			
				switch (event.getString("origin")) {
				case Event.CRITICAL:
				case Event.SNMP:
				case Event.STATUS:
				case Event.IFMON:
					JSONObject userData;
					try {
						userData = this.agent.getUser(true);
										
						ArrayList<String> list = new ArrayList<>();
						JSONObject user;
						
						for (Object name : userData.keySet()) {
							user = userData.getJSONObject((String)name);
							
							if (user.has("email")) {
								list.add(user.getString("email"));
							}
						}
						
						if (list.size() > 0) {
							String [] sa = new String [list.size()];
							
							list.toArray(sa);
							
							smtpServer.send(String.format("%s %s",
								event.has("name")? event.getString("name"):
								event.has("ip")? event.getString("ip"): "",
								event.getString("message")), sa);
						}
					} catch (SQLException sqle) {
						sqle.printStackTrace();
					}

					break;
				}
				
				synchronized(this) {
					this.event = event.toString();
						
					notifyAll();
				}
			
			}
		}
	}
	
	public static boolean isExeedLimit(int size) {
		return size > LIMIT && LIMIT > 0;
	}
}
