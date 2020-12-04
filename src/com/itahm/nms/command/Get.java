package com.itahm.nms.command;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.Commander;
import com.itahm.service.NMS;

public class Get implements Executor {
	private final Map<String, Helper> map = new HashMap<>();
	
	public Get(Commander agent) {
		map.put("AUDIT", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				try (PreparedStatement pstmt = connection.prepareStatement("SELECT"+
					" username, command, target, timestamp"+
					" FROM t_audit"+
					" WHERE timestamp>=? AND timestamp<?"+
					";")) {
					pstmt.setLong(1, request.getLong("from"));
					pstmt.setLong(2, request.getLong("to"));
					
					try (ResultSet rs = pstmt.executeQuery()) {
						JSONObject result = new JSONObject();
						
						while (rs.next()) {
							result.put(Long.toString(rs.getLong(4)), new JSONObject()
								.put("username", rs.getString(1))
								.put("command", rs.getString(2))
								.put("target", rs.getString(3))
								.put("timestamp", rs.getLong(4)));
						}
						
						return result;
					}
				}
			}
			
		});

		map.put("BRANCH", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getBranch(request.getLong("id")):
					agent.getBranch();
			}
			
		});
		
		map.put("CONFIG", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) {
				return agent.getConfig().put("smtpRunning", NMS.smtpServer.isRunning());
			}
			
		});
		
		map.put("EVENT", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("date")?
					agent.getEventByDate(request.getLong("date")):
					agent.getEvent(request.getJSONObject("search"));
			}
			
		});

		map.put("FACILITY", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getFacility(request.getLong("id")):
					agent.getFacility();
			}
			
		});

		
		map.put("ICON", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("type")?
					agent.getIcon(request.getString("type")):
					agent.getIcon();
			}
			
		});
		
		map.put("INFORMATION", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) {
				JSONObject result = agent.getInformation();
				
				result
					.put("version", NMS.VERSION)
					.put("java", System.getProperty("java.version"))
					.put("expire", NMS.EXPIRE)
					.put("limit", NMS.LIMIT)
					.put("path", agent.getRoot().toString());
				try {
					result.put("space", Files.getFileStore(agent.getRoot()).getUsableSpace());
				} catch (IOException ioe) {
					ioe.printStackTrace();
				}
				
				return result;
			}
			
		});
		
		map.put("LIMIT", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getLimit(request.getLong("id"), request.getString("oid"), request.getString("index")):
					agent.getLimit();
			}
			
		});
		
		map.put("LINK", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("path")?
					agent.getLink(request.getLong("path")):
					agent.getLink();
			}
			
		});
		
		map.put("LOCATION", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("rack")?
					agent.getLocation(request.getLong("rack")):
					agent.getLocation();
			}
			
		});
		
		map.put("LOG", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return agent.getEventByDate(request.getLong("date"));
			}
			
		});

		map.put("MANAGER", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				JSONObject managerData = agent.getManager(request.getLong("node"));
				
				if (managerData == null) {
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
					
					return null;
				} else {
					return managerData;
				}
			}
			
		});
		
		map.put("NODE", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getNode(request.getLong("id"), request.has("resource") && request.getBoolean("resource")):
					agent.getNode(request.has("filter")? request.getString("filter"): null);
			}
			
		});
		
		map.put("PATH", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("nodeFrom")?
					agent.getPath(request.getLong("nodeFrom"), request.getLong("nodeTo")):
					agent.getPath();
			}
			
		});
		
		map.put("POSITION", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return agent.getPosition(request.getString("name"));
			}
			
		});
		
		map.put("PROFILE", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("name")?
					agent.getProfile(request.getString("name")):
					agent.getProfile();

			}
			
		});
		
		map.put("RACK", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getRack(request.getInt("id")):
					agent.getRack();
			}
			
		});
		
		map.put("RESOURCE", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) {
				return request.has("date")?
						agent.getResource(request.getLong("id"),
							request.getString("oid"),
							request.getString("index"),
							request.getLong("date")):
					request.has("from") && request.has("to")?
						agent.getResource(request.getLong("id"),
							request.getString("oid"),
							request.getString("index"),
							request.getLong("from"),
							request.getLong("to")):
						agent.getResource(request.getLong("id"),
							request.getString("oid"),
							request.getString("index"));
			}
			
		});
		
		map.put("SETTING", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("key")?
					agent.getSetting(request.getString("key")):
					agent.getSetting();
			}			
		});
		
		map.put("TOP", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) {
				return agent.getTop(request.getInt("count"));
			}
			
		});
		
		map.put("TRAFFIC", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) {
				return agent.getTraffic(request.getJSONObject("traffic"));
			}
			
		});
		
		map.put("USER", new Helper() {

			@Override
			public JSONObject execute(Response response, JSONObject request, Connection connection) throws SQLException {
				return request.has("id")?
					agent.getUser(request.getString("id")):
					agent.getUser(false);
			}
			
		});
	}
	
	@Override
	public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
		throws SQLException {
		Executor executor = this.map.get(request.getString("target").toUpperCase());
		
		if (executor == null) {
			throw new JSONException("Target is not found.");
		} else {
			executor.execute(response, request, session, connection);
		}
	}
	
	abstract class Helper implements Executor {
		abstract public JSONObject execute(Response response, JSONObject request, Connection connection)
			throws SQLException;
		
		@Override
		public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
			throws SQLException {
			
			JSONObject result = execute(response, request, connection);
			
			if (result == null) {
				response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			} else {
				response.write(result);
			}
		}
		
	}
}
