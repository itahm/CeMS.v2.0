package com.itahm.nms.parser;

import java.util.ArrayList;

import com.itahm.nms.Bean.Counter;
import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.ResourceManager;

public class IFErrorsIn extends AbstractParser {
	
	private String [] OIDS = new String [] {
			"1.3.6.1.2.1.2.2.1.14",
			"1.3.6.1.4.1.49447.3.3"
		};
	
	public IFErrorsIn(ResourceManager resourceManager) {
		super(resourceManager);
	}

	public IFErrorsIn(ResourceManager resourceManager, String [] oids) {
		this(resourceManager);
		
		OIDS = oids;
	}
	
	@Override
	public ArrayList<CriticalEvent> parse(long id) {
		ArrayList<CriticalEvent> list = new ArrayList<>();
		
		super.resourceManager.forEachIndex(id, OIDS[0], (index, value) -> {
			CriticalEvent ce = parse(id, index, value);
			
			if (ce != null) {
				list.add(ce);
			}
		});
		
		return list;
	}
	
	public CriticalEvent parse(long id, String index, Value value) {
		ArrayList<Value> list = super.resourceManager.getByIndex(id, index, OIDS[1]);
		
		if (!(value instanceof Counter) || list == null) {
			return null;
		}
		
		Long cps = ((Counter)value).counter();
		
		if (cps == null) {
			return null;
		}
		
		Max max = this.max.get(id);
		
		if (max == null || max.value < cps) {
			this.max.put(id, new Max(id, index, cps));
		}
		
		Value cpsValue = list.get(0);
		
		if (cpsValue == null) {
			super.resourceManager.getValue(id, OIDS[1], index, true)
				.set(value.timestamp, Long.toString(cps));
		} else {
			cpsValue.set(value.timestamp, Long.toString(cps));
			
			if (cpsValue.limit > 0) {
				boolean critical = cps > cpsValue.limit;
			
				if (cpsValue.critical != critical) {
					cpsValue.critical = critical;
					
					return new CriticalEvent(id, index, OIDS[1], critical, String.format("%s %dcps", getEventTitle(), cps));
				}
			} else if (cpsValue.critical) {
				cpsValue.critical = false;
				
				return new CriticalEvent(id, index, OIDS[1], false, String.format("%s %dcps", getEventTitle(), cps));
			}
		}
		
		return null;
	}
	
	protected String getEventTitle() {
		return "수신 오류";	
	}
	
}
