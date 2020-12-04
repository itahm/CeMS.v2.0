package com.itahm.nms.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.Commander;
import com.itahm.service.NMS;

public class Set implements Executor {
	private final Map<String, Executor> map = new HashMap<>();
	
	public Set(Commander agent ) {
		map.put("BANDWIDTH", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (request.has("value")) {
					if (!agent.setBandwidth(request.getLong("id"),
						request.getString("index"),
						request.getString("value"))) {
						response.setStatus(HttpServletResponse.SC_CONFLICT);
					}
				} else {
					if (!agent.removeBandwidth(request.getLong("id"),
						request.getString("index"))) {
						response.setStatus(HttpServletResponse.SC_CONFLICT);
					}
				}
			}
			
		});
		
		map.put("BRANCH", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setBranch(request.getLong("id"), request.getJSONObject("branch"))) {
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			}
			
		});
		
		map.put("CONFIG", new Executor() {
			private final Executor config = new Executor () {
				private final Map<String, Executor> map = new HashMap<>();
				
				{
					map.put("IFMON", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							agent.setIFMon(request.getBoolean("value"));
						}
						
					});
					
					map.put("RETRY", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!agent.setRetry(request.getInt("value"))) {
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							}
						}
						
					});
					
					map.put("STOREDATE", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!agent.setStoreDate(Integer.valueOf(request.getString("value")))) {
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							}
						}
						
					});
					
					map.put("SAVEINTERVAL", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!agent.setSaveInterval(Integer.valueOf(request.getString("value")))) {
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							}
						}
						
					});
					
					map.put("REQUESTINTERVAL", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!agent.setRequestInterval(request.getLong("value"))) {
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							}
						}
						
					});
					
					map.put("SMTPSERVER", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!request.has("value")) {
								if (agent.setSMTP(null)) {
									NMS.smtpServer.disable();
								} else {
									response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
								}
							} else {
								JSONObject smtp = request.getJSONObject("value");
								
								if (agent.setSMTP(smtp)) {
									if (!NMS.smtpServer.enable(
										smtp.getString("server"),
										smtp.getString("protocol"),
										smtp.getString("user"),
										smtp.getString("password")
									)) {
										// DB 는 수정 되었으나 정상동작하지 않는다. isRunning() = false
										response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
									}
								} else {
									response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);	
								}
							}
						}
						
					});
					
					map.put("TIMEOUT", new Executor() {

						@Override
						public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
							if (!agent.setTimeout(request.getInt("value"))) {
								response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
							}
						}
						
					});
				}
				
				@Override
				public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
					
					Executor executor = this.map.get(request.getString("key").toUpperCase());
					
					if (executor == null) {
						throw new JSONException("Config is not found.");
					} else {
						executor.execute(response, request, session, connection);
					}
				}
				
			};
			
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				this.config.execute(response, request, session, connection);
			}
			
		});
		
		map.put("FACILITY", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)  throws SQLException{
				agent.setFacility(request.getLong("id"), request.getJSONObject("facility"));
			}
			
		});
		
		map.put("ICON", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setIcon(request.getString("type"), request.getJSONObject("icon"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("LIMIT", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setLimit(request.getLong("id"),
						request.getString("oid"),
						request.getString("index"),
						request.getInt("limit"))) {
						response.setStatus(HttpServletResponse.SC_CONFLICT);
					}
			}
			
		});
		
		map.put("LINK", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setLink(request.getJSONObject("link"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("LOCATION", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws JSONException, SQLException {
				agent.setLocation(request.getLong("node"), request.getJSONObject("location"));
			}
			
		});
		
		map.put("MANAGER", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setManager(request.getLong("node"), request.getString("user"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("MONITOR", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
				throws SQLException {
				if (!agent.setMonitor(request.getLong("id"), request.getString("ip"), request.has("protocol")? request.getString("protocol"): null)) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("NODE", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setNode(request.getLong("id"), request.getJSONObject("node"))) {
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			}
			
		});
		

		map.put("PATH", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setPath(request.getJSONObject("path"))) {
					response.setStatus(HttpServletResponse.SC_CONFLICT);
				}
			}
			
		});
		
		map.put("POSITION", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws JSONException, SQLException {
				agent.setPosition(request.getString("name"), request.getJSONObject("position"));
			}
			
		});
		
		map.put("SETTING", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setSetting(request.getString("key"), request.has("value")? request.getString("value"): null)) {
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			}
			
		});
		
		map.put("RACK", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (request.has("id")) {
					if (!agent.setRack(request.getInt("id"), request.getJSONObject("rack"))) {
						response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
					}
				} else {
					if (!agent.setRack(request.getJSONObject("rack"))) {
						response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
					}
				}
			}
			
		});
		
		map.put("USER", new Executor() {

			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				if (!agent.setUser(request.getString("id"), request.getJSONObject("user"))) {
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			}
			
		});
	}
	
	@Override
	public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
		String target = request.getString("target");
		Executor executor = this.map.get(target.toUpperCase());
		
		if (executor == null) {
			throw new JSONException("Target is not found.");
		} else {
			executor.execute(response, request, session, connection);
			
			try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO"+
				" t_audit values (?, 'set', ?, ?);")) {
				pstmt.setString(1, session.getString("username"));
				pstmt.setString(2, target);
				pstmt.setLong(3, System.currentTimeMillis());
				
				pstmt.executeUpdate();
			}
		}
	}
}
