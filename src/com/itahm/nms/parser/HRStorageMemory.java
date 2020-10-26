package com.itahm.nms.parser;

import com.itahm.nms.ResourceManager;

public class HRStorageMemory extends HRStorage {
	
	private final static String OID_MEMORY = "1.3.6.1.2.1.25.2.1.2";
	
	public HRStorageMemory(ResourceManager resourceManager) {
		super(resourceManager);
	}

	@Override
	public String toString() {
		return "HRSTORAGEMEMORY";
	}
	
	@Override
	protected boolean isValidType(String oid) {
		return OID_MEMORY.equals(oid);
	}
	
	@Override
	protected String getEventTitle() {
		return "물리 메모리";
	}
}
