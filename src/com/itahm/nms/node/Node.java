package com.itahm.nms.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.itahm.util.Listenable;
import com.itahm.util.Listener;

abstract public class Node implements Runnable, Closeable, Listenable {

	public static enum Action {
		PING, SNMP, RESOURCE, CLOSE;
	};
	public final long id;
	protected Boolean isClosed = false;
	protected int timeout = 5000;
	protected int retry = 1;
	protected final Thread thread;
	private boolean status = true;
	private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
	private final ArrayList<Listener> listenerList = new ArrayList<>();
	
	public Node(long id, String name) {
		this.id = id;
		
		thread = new Thread(this);
		
		thread.setName(name);
		
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void run() {
		long delay, sent;
		
		loop: while (!this.thread.isInterrupted()) {
			try {
				delay = this.queue.take();
				
				if (delay > 0) {
					Thread.sleep(delay);
				}
				else if (delay < 0) {
					break loop;
				}
				
				for (int i=-1; i<this.retry; i++) {
					if (this.isClosed) {
						break loop;
					}
					
					try {
						sent = System.currentTimeMillis();
						
						if (isReachable()) {
							fireEvent(System.currentTimeMillis() - sent);
							
							continue loop;
						}
					} catch (IOException ie) {
						ie.printStackTrace();
					}
				}
				
				fireEvent(Long.valueOf(-1));
			} catch (InterruptedException ie) {
				break;
			}
		}
	}
	
	private void fireEvent(long rtt) {
		boolean status = rtt > -1;
		boolean issue = false;
		
		if (this.status != status) {
			this.status = status;
			
			issue = true;
		}
		
		fireEvent(Action.PING, rtt, issue);
	}
	
	@Override
	public void addEventListener(Listener listener) {
		this.listenerList.add(listener);
	}
	
	@Override
	public void removeEventListener(Listener listener) {
		this.listenerList.remove(listener);
	}

	@Override
	public void fireEvent(Object ...args) {
		synchronized (this.isClosed) {
			if (this.isClosed) {
				return;
			}
		}
		
		for (Listener listener: this.listenerList) {
			listener.onEvent(this, args);
		}
	}
	
	public void setTimeout(int i) {
		this.timeout = i;
	}
	
	public void setRetry(int i) {
		this.retry = i;
	}
	
	public void setStatus(boolean status) {
		this.status = status;
	}
	
	public void ping(long delay) {
		try {
			this.queue.put(delay);
		} catch (InterruptedException ie) {
			this.thread.interrupt();
		}
	}
	
	@Override
	public void close() {
		close(false);
	}
	
	public void close(boolean wait) {
		synchronized (this.isClosed) {
			if (this.isClosed) {
				return;
			}
			
			this.isClosed = true;
		}
		
		try {
			this.queue.put(-1L);
			
			if (wait) {
				this.thread.join();
			}
		} catch (InterruptedException ie) {
			this.thread.interrupt();
		}
		
		fireEvent(Action.CLOSE);
	}
	
	abstract public boolean isReachable() throws IOException;
}
