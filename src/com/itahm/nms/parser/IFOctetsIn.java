package com.itahm.nms.parser;

import java.util.ArrayList;

import com.itahm.nms.Bean.Counter;
import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;
import com.itahm.nms.Bean.Value;
import com.itahm.nms.ResourceManager;

public class IFOctetsIn extends AbstractParser2 {
	
	protected String OID = "1.3.6.1.2.1.2.2.1.10";
	protected String [] OIDS = new String [] {
			"1.3.6.1.4.1.49447.3.5", // BANDWIDTH
			"1.3.6.1.2.1.31.1.1.1.15", //IFHIGHSPEED
			"1.3.6.1.2.1.2.2.1.5", //IFSPEED
			"1.3.6.1.2.1.31.1.1.1.6", // IFHCINOCTETS
			"1.3.6.1.4.1.49447.3.1" // IFINBPS
		};
	
	public IFOctetsIn(ResourceManager resourceManager) {
		super(resourceManager);
	}

	protected IFOctetsIn(ResourceManager resourceManager, String oid, String [] oids) {
		this(resourceManager);
		
		this.OID = oid;
		this.OIDS = oids;
	}
	
	@Override
	public ArrayList<CriticalEvent> parse(long id) {
		ArrayList<CriticalEvent> list = new ArrayList<>();
		
		super.resourceManager.forEachIndex(id, OID, (index, value) -> {
			CriticalEvent ce = parse(id, index, value);
			
			if (ce != null) {
				list.add(ce);
			}
		});
		
		return list;
	}
	
	public CriticalEvent parse(long id, String index, Value value) {
		ArrayList<Value> list = super.resourceManager.getByIndex(id, index, OIDS);
		
		long speed = 0;
		Value v;
		
		try {
			
			
			v = list.get(0);
			if (v == null){
				v = list.get(1);
				
				if (v == null) {
					v = list.get(2);
					
					if (v == null) {
						return null;
					} else {
						speed = Long.valueOf(v.value);
					}
				} else {
					speed = Long.valueOf(v.value) *1000000;
				}
			} else {
				speed = Long.valueOf(v.value);
			}
			
			if (speed <= 0) {
				return null;
			}
		} catch (NumberFormatException nfe) {
			return null;
		}
		
		v = list.get(3);
		
		if (v != null) {
			value = v;
		}
		
		if (!(value instanceof Counter)) {
			return null;
		}
		
		Long bps = ((Counter)value).counter();
		
		if (bps == null) {
			return null;
		}
		
		bps *= 8;
		
		Max max = this.max.get(id);
		
		if (max == null || max.value < bps) {
			this.max.put(id, new Max(id, index, bps, bps *100 / speed));
		}
		
		max = this.maxRate.get(id);
		
		if (max == null || max.rate < bps *100 / speed) {
			this.maxRate.put(id, new Max(id, index, bps, bps *100 / speed));
		}
		
		Value bpsValue = list.get(4);
		
		if (bpsValue == null) {
			this.resourceManager.getValue(id, OIDS[4], index, true)
				.set(value.timestamp, Long.toString(bps));
		} else {
			bpsValue.set(value.timestamp, Long.toString(bps));
			
			if (bpsValue.limit > 0) {
				boolean critical = bps *100 / speed > bpsValue.limit;
			
				if (bpsValue.critical != critical) {
					bpsValue.critical = critical;
					
					return new CriticalEvent(id, index, OIDS[4], critical,
						String.format("%s %d%%", getTitle(), bps *100 / speed));
				}
			} else if (bpsValue.critical) {
				bpsValue.critical = false;
				
				return new CriticalEvent(id, index, OIDS[4], false,
					String.format("%s %d%%", getTitle(), bps *100 / speed));
			}	
		}
		
		return null;
	}

	protected String getTitle() {
		return "수신";
	}
	
	@Override
	public String toString() {
		return "IFINOCTETS";
	}

}
