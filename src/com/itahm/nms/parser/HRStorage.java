package com.itahm.nms.parser;

import java.util.ArrayList;

import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.ResourceManager;

public class HRStorage extends AbstractParser2 {
	
	private final static String OID_TYPE = "1.3.6.1.2.1.25.2.3.1.2";
	private final static String OID_USED = "1.3.6.1.2.1.25.2.3.1.6";
	private final static String OID_SIZE = "1.3.6.1.2.1.25.2.3.1.5";
	private final static String OID_UNIT = "1.3.6.1.2.1.25.2.3.1.4";
	private final static String OID_STORAGE = "1.3.6.1.2.1.25.2.1.4";
	private final static String [] OIDS = new String [] {
			OID_UNIT,
			OID_SIZE,
			OID_USED
		};
	
	public HRStorage(ResourceManager resourceManager) {
		super(resourceManager);
	}
	
	@Override
	public ArrayList<CriticalEvent> parse(long id) {
		ArrayList<CriticalEvent> list = new ArrayList<> ();
		
		super.resourceManager.forEachIndex(id, OID_TYPE, (index, value) -> {
			CriticalEvent ce = parse(id, index, value);
			
			if (ce != null) {
				list.add(ce);
			}
		});
		
		return list;
	}
	
	public CriticalEvent parse(long id, String index, Value value) {
		ArrayList<Value> list = super.resourceManager.getByIndex(id, index, OIDS);
		
		if (list == null) {
			return null;
		}
		
		if (!isValidType(value.value)) {
			return null;
		}
		
		long units, size, used;
		
		try {			
			value = list.get(0);
			
			if (value == null) {
				return null;
			}
			
			units = Long.valueOf(value.value);
			
			value = list.get(1);
			
			if (value == null) {
				return null;
			}
			
			size = Long.valueOf(value.value);
			
			if (size <= 0) {
				return null;
			}
			
			value = list.get(2);
			
			if (value == null) {
				return null;
			}
			
			used = Long.valueOf(value.value);
		} catch (NumberFormatException nfe) {
			return null;
		}
					
		Max max = this.max.get(id);
							
		if (max == null || max.value < used * units) {
			this.max.put(id, new Max(id, index, used * units, used *100 / size));
		}
		
		max = this.maxRate.get(id);
		
		if (max == null || max.rate < used *100 / size) {
			this.maxRate.put(id, new Max(id, index, used * units, used *100 / size));
		}
		
		if (value.limit > 0) {
			boolean critical = used *100 / size > value.limit;
			
			if (critical != value.critical) {
				value.critical = critical;
				
				return new CriticalEvent(id, index, "1.3.6.1.2.1.25.2.3.1.6",
					critical, String.format("%s %d%%", getEventTitle(), used *100 / size));
			}
		} else if (value.critical) {
			value.critical = false;
			
			return new CriticalEvent(id, index, "1.3.6.1.2.1.25.2.3.1.6",
				false, String.format("%s %d%%", getEventTitle(), used *100 / size));
		}
		
		return null;
	}

	@Override
	public String toString() {
		return "HRSTORAGEUSED";
	}
	
	protected boolean isValidType(String oid) {
		return OID_STORAGE.equals(oid);
	}
	
	protected String getEventTitle() {
		return "저장 공간";
	}
}
