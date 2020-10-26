package com.itahm.nms.parser;

import java.util.ArrayList;
import java.util.List;

import com.itahm.nms.Bean.CriticalEvent;
import com.itahm.nms.Bean.Max;

public interface Parseable {
	public List<Max> getTop(int count, boolean byRate);
	public ArrayList<CriticalEvent> parse(long id);
	public void submit(long id);
	public void reset(long id);
}
