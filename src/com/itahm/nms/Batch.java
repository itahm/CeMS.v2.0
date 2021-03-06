package com.itahm.nms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.h2.jdbcx.JdbcConnectionPool;

import com.itahm.json.JSONObject;

public class Batch extends Timer {

	private final String JDBC_URL = "jdbc:h2:%s";
	private final String SQL_ROLLING = "INSERT INTO"+
		" t_rolling"+
		" (id, oid, _index, value, timestamp)"+
		" VALUES (?, ?, ?, ?, ?);";
	private final String SQL_SUMMARY = "MERGE INTO"+
		" t_summary"+
		" (node, oid, _index, max, avg, min, timestamp)"+
		" KEY (node, oid, _index, timestamp)"+
		" VALUES (?, ?, ?, ?, ?, ?, ?);";
	private final Path path;
	private final ResourceManager resourceManager;
	private Saver saver;
	private int storeDate = 0;
	private long summaryTime;
	private final JdbcConnectionPool summaryPool;
	private JdbcConnectionPool connPool;
	private JdbcConnectionPool nextPool;
	
	public Batch(Path p, ResourceManager rm) throws SQLException {
		super("Batch Scheduler");

		path = p;
		resourceManager = rm;
		
		summaryPool = JdbcConnectionPool.create(String.format(JDBC_URL, p.resolve("summary").toString()), "sa", "");
		
		try (Connection c = summaryPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_summary"+
					" (node BIGINT NOT NULL"+
					", oid VARCHAR NOT NULL"+
					", _index VARCHAR NOT NULL"+
					", max BIGINT NOT NULL"+
					", avg BIGINT NOT NULL"+
					", min BIGINT NOT NULL"+
					", timestamp BIGINT DEFAULT NULL"+
					", CONSTRAINT UQ_SUM UNIQUE(node, oid, _index, timestamp)"+
					");");
			}
		}
		
		Calendar c = Calendar.getInstance();
		
		p = p.resolve(String.format("%04d-%02d-%02d"
			, c.get(Calendar.YEAR)
			, c.get(Calendar.MONTH) +1
			, c.get(Calendar.DAY_OF_MONTH)));
		
		connPool = JdbcConnectionPool.create(String.format(JDBC_URL, p.toString()), "sa", "");
		
		createRollingTable();
		
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		summaryTime = c.getTimeInMillis();
		
		c.add(Calendar.HOUR_OF_DAY, 1);
		
		super.scheduleAtFixedRate(new Hourly(this), c.getTime(), TimeUnit.HOURS.toMillis(1));
		
		c = Calendar.getInstance();
		
		c.add(Calendar.DATE, 1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);		
		
		super.scheduleAtFixedRate(new Daily(this), c.getTime(), TimeUnit.DAYS.toMillis(1));
	}
	
	@Override
	public void cancel() {
		super.cancel();
		
		synchronized(this.connPool) {
			this.connPool.dispose();
			
			this.connPool = null;
		}
		
		this.nextPool.dispose();
	}
	
	private void createRollingTable() throws SQLException {
		try (Connection c = this.connPool.getConnection()) {
			try (Statement stmt = c.createStatement()) {
				stmt.executeUpdate("CREATE TABLE IF NOT EXISTS t_rolling"+
					" (id BIGINT NOT NULL"+
					", oid VARCHAR NOT NULL"+
					", _index VARCHAR NOT NULL"+
					", value BIGINT NOT NULL"+
					", timestamp BIGINT DEFAULT NULL);");
			}
		}
		
		Calendar c = Calendar.getInstance();

		c.add(Calendar.DATE, 1);
		
		Path path = this.path.resolve(String.format("%04d-%02d-%02d"
			, c.get(Calendar.YEAR)
			, c.get(Calendar.MONTH) +1
			, c.get(Calendar.DAY_OF_MONTH)));
		
		this.nextPool = JdbcConnectionPool.create(String.format(JDBC_URL, path.toString()), "sa", "");
	}
	
	private void reset() throws SQLException {
		JdbcConnectionPool pool;
		
		synchronized(this.connPool) {
			pool = this.connPool;
			
			this.connPool = this.nextPool;
		}
		
		pool.dispose();
		
		createRollingTable();
	}
	
	public JSONObject getData(long id, String oid, String index, long date) {
		Calendar calendar = Calendar.getInstance();
		JSONObject result = new JSONObject();
		int year = calendar.get(Calendar.YEAR);
		int day = calendar.get(Calendar.DAY_OF_YEAR);
		
		calendar.setTimeInMillis(date);
		
		try (Connection c = year == calendar.get(Calendar.YEAR) && day == calendar.get(Calendar.DAY_OF_YEAR)?
			this.connPool.getConnection():
			DriverManager.getConnection(
			String.format("jdbc:h2:%s\\%04d-%02d-%02d;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;",
				this.path.toString(),
				calendar.get(Calendar.YEAR),
				calendar.get(Calendar.MONTH) +1,
				calendar.get(Calendar.DAY_OF_MONTH)), "sa", "")) {
			
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" value, timestamp"+
				" FROM t_rolling"+
				" WHERE id=? AND _index=? AND oid=?;")) {
				pstmt.setLong(1, id);
				pstmt.setString(2, index);
				pstmt.setString(3, oid);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					while (rs.next()) {
						result.put(Long.toString(rs.getLong(2)), rs.getLong(1));
					}
				}
			}
		} catch (SQLException sqle) {
		}
		
		return result;
	}
	
	public JSONObject getSummary(long id, String oid, String index, long from, long to) {
		try (Connection c = this.summaryPool.getConnection()) {
			try (PreparedStatement pstmt = c.prepareStatement("SELECT"+
				" max, avg, min, timestamp"+
				" FROM t_summary"+
				" WHERE node=? AND _index=? AND oid=? AND timestamp >= ? AND timestamp < ?;")) {
				pstmt.setLong(1, id);
				pstmt.setString(2, index);
				pstmt.setString(3, oid);
				pstmt.setLong(4, from);
				pstmt.setLong(5, to);
				
				try (ResultSet rs = pstmt.executeQuery()) {
					JSONObject
						summaryData = new JSONObject();
					while (rs.next()) {
						summaryData.put(Long.toString(rs.getLong(4)),
							new JSONObject()
								.put("max", rs.getLong(1))
								.put("avg", rs.getLong(2))
								.put("min", rs.getLong(3)));
					}
					
					return summaryData;
				}
			}
		} catch (SQLException sqle) {
			sqle.printStackTrace();
		}
		
		return null;
	}
	
	private void remove() {
		if (this.storeDate <= 0) {
			return;
		}

		Calendar c = Calendar.getInstance();
		long millis;
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) - this.storeDate);
		
		millis = c.getTimeInMillis();
		
		try {
			Files.list(this.path).
				filter(Files::isRegularFile).forEach(p -> {
					try {
						FileTime ft = Files.getLastModifiedTime(p);
						
						if (millis > ft.toMillis()) {
							System.out.println(String.format("Database %s expired.", p.getFileName()));
							
							Files.delete(p);
						}
					} catch (IOException ioe) {
						ioe.printStackTrace();
					}
				});
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	public void schedule(long period) {
		if (this.saver != null) {
			this.saver.cancel();
		}
		
		this.saver = new Saver(this);
		
		super.schedule(this.saver, period, period);
	}
	
	public void setStoreDate(int period) {
		this.storeDate = period;
		
		remove();
	}
	
	/*
	private void save() {
		Map<String, Map<String, Value>> indexMap;
		Map<String, Value> oidMap;
		Value v;
		Rollable r;

		synchronized(this.connPool) {
			if (this.connPool == null) {
				return;
			}

			long ttt = System.currentTimeMillis();
			try(Connection c1 = this.connPool.getConnection()) {
				try (PreparedStatement pstmt1 = c1.prepareStatement(SQL_ROLLING)) {
					try (Connection c2 = this.summaryPool.getConnection()) {
						try (PreparedStatement pstmt2 = c2.prepareStatement(SQL_SUMMARY)) {
							for (Long id: this.resourceMap.keySet()) {
								pstmt1.setLong(1, id);
								pstmt2.setLong(1, id);
								
								indexMap = this.resourceMap.get(id);
								 
								for (String index : indexMap.keySet()) {
									pstmt1.setString(3, index);
									pstmt2.setString(3, index);
									
									oidMap = indexMap.get(index);
									 
									for (String oid : oidMap.keySet()) {
										pstmt1.setString(2, oid);
										pstmt2.setString(2, oid);
										
										v = oidMap.get(oid);
										 
										if (v instanceof Rollable) {
											r = (Rollable)v;
											
											pstmt1.setString(4, v.value);
											pstmt1.setLong(5, v.timestamp);
											pstmt1.addBatch();
											
											pstmt2.setLong(4, r.max());
											pstmt2.setLong(5, r.avg());
											pstmt2.setLong(6, r.min());
											pstmt2.setLong(7, this.summaryTime);
											pstmt2.addBatch();
										}
									}
								 }
							}
							
							pstmt1.executeBatch();
							pstmt2.executeBatch();
						}
					}
				}
			} catch (SQLException sqle) {
				sqle.printStackTrace();
			} finally {System.out.format("SAVE3\t%dms\n", System.currentTimeMillis() - ttt);
				if (System.currentTimeMillis() - ttt > 30000) {
					new Exception().printStackTrace();
				}
			}
		}
	}
	*/
	private void save() {
		synchronized(this.connPool) {
			if (this.connPool == null) {
				return;
			}

			long ttt = System.currentTimeMillis();
			try(Connection c1 = this.connPool.getConnection()) {
				try (PreparedStatement pstmt1 = c1.prepareStatement(SQL_ROLLING)) {
					try (Connection c2 = this.summaryPool.getConnection()) {
						try (PreparedStatement pstmt2 = c2.prepareStatement(SQL_SUMMARY)) {
							this.resourceManager.forEachRollables(rollable -> {
								try {
									pstmt1.setLong(1, rollable.id);
									pstmt1.setString(2, rollable.oid);
									pstmt1.setString(3, rollable.index);
									try {
										pstmt1.setLong(4, Long.valueOf(rollable.value));
										pstmt1.setLong(5, rollable.timestamp);
										pstmt1.executeUpdate();
									} catch (NumberFormatException nfe) {}
									
									pstmt2.setLong(1, rollable.id);
									pstmt2.setString(2, rollable.oid);
									pstmt2.setString(3, rollable.index);
									pstmt2.setLong(4, rollable.max());
									pstmt2.setLong(5, rollable.avg());
									pstmt2.setLong(6, rollable.min());
									pstmt2.setLong(7, this.summaryTime);
									pstmt2.executeUpdate();
								} catch (SQLException sqle) {
									sqle.printStackTrace();
								}
							});
						}
					}
				}
			} catch (SQLException sqle) {
				sqle.printStackTrace();
			} finally {
				if (System.currentTimeMillis() - ttt > 30000) {
					new Exception(Long.toString(System.currentTimeMillis() - ttt)).printStackTrace();
				}
			}
		}
	}
	
	private void summarize(long timestamp) {
		this.summaryTime = timestamp;
		
		this.resourceManager.clear();
	}
	
	private static class Hourly extends TimerTask {
		private final Batch batch;
		
		public Hourly(Batch batch) {
			this.batch = batch;
		}
		
		@Override
		public void run() {
			Calendar c = Calendar.getInstance();
			
			c.set(Calendar.MINUTE, 0);
			c.set(Calendar.SECOND, 0);
			c.set(Calendar.MILLISECOND, 0);
			
			this.batch.summarize(c.getTimeInMillis());
		}
	}
	
	private static class Daily extends TimerTask {
		private final Batch batch;
		
		public Daily(Batch batch) {
			this.batch = batch;
		}
		
		@Override
		public void run() {
			this.batch.remove();
			
			try {
				this.batch.reset();
			} catch (SQLException sqle) {
				sqle.printStackTrace();
			}
		}
	}
	
	private static class Saver extends TimerTask {

		private final Batch batch;
		
		public Saver(Batch batch) {
			this.batch = batch;
		}
		
		@Override
		public void run() {
			this.batch.save();
		}
		
	}
	
}
