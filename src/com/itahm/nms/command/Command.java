package com.itahm.nms.command;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.h2.jdbcx.JdbcConnectionPool;

import com.itahm.http.Reques;
import com.itahm.http.Response;
import com.itahm.http.Session;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.nms.Commander;

public class Command implements Closeable {
	private final JdbcConnectionPool connPool;
	
	private final Map<String, Executor> map = new HashMap<>();
	
	
	public Command(Commander commander, Path root) {		
		connPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s", root.resolve("audit").toString()), "sa", "");
		
		try (Connection c = connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_audit"+
					" (username VARCHAR NOT NULL"+
					", command VARCHAR NOT NULL"+
					", target VARCHAR NOT NULL"+
					", timestamp BIGINT NOT NULL);");
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		}
		
		map.put("ADD", new Executor() {
			private final Executor add = new Add(commander);
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				add.execute(response, request, session, connection);
			}
			
		});
		
		map.put("BACKUP", new Executor() {
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				try {
					commander.backup();
				} catch (Exception e) {
					e.printStackTrace();
					
					response.write(new JSONObject().
						put("error", e.getMessage()));
					
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			}
			
		});
		
		map.put("ECHO", new Executor() {
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) {
				
			}
			
		});
		
		map.put("GET", new Executor() {
			private final Executor get = new Get(commander);
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				get.execute(response, request, session, connection);
			}
			
		});
		
		map.put("REMOVE", new Executor() {
			private final Executor remove = new Remove(commander);
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				remove.execute(response, request, session, connection);
			}
			
		});
		
		map.put("RESTORE", new Executor() {
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
			}
			
		});
		
		map.put("SEARCH", new Executor() {
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection)
				throws SQLException {
				commander.search(
					request.getString("network"),
					request.getInt("mask"),
					request.has("profile")? request.getString("profile"): null);
			}
			
		});
		
		map.put("SET", new Executor() {
			private final Executor set = new Set(commander);
			
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				set.execute(response, request, session, connection);
			}
			
		});
		
		map.put("TEST", new Executor() {
			@Override
			public void execute(Response response, JSONObject request, JSONObject session, Connection connection) throws SQLException {
				commander.test(request);
			}
			
		});
	}
	
	public final boolean execute(Reques request, Response response, JSONObject data) {
		Session session = request.getSession(false);
		
		if (session == null) {
			throw new JSONException("No session.");
		}
		
		JSONObject account = (JSONObject)session.getAttribute("account");
		Executor executor = this.map.get(data.getString("command").toUpperCase());
		
		if (executor == null) {
			return false;
		} else {
			try (Connection c = this.connPool.getConnection()) {
				executor.execute(response, data, account, c);
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
		
		return true;
	}

	@Override
	public void close() throws IOException {
		this.connPool.dispose();
	}
}
