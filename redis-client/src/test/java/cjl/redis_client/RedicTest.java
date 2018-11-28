package cjl.redis_client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedicTest {
	
	public static void main(String args[]) throws InterruptedException{
		
		Map map =  new HashMap();
		map.put("master", "m1");
		
		List<String> sentinels = new ArrayList();
		sentinels.add("172.16.161.160:26379");
		
		map.put("sentinels", sentinels);
		
		List<Map> masters = new ArrayList();
		
		masters.add(map);
		
		
		Redic redic = new Redic(masters);
		
		
		redic.set("test", "test_value");
		
		System.out.println(redic.get("test"));
		
		
		
		List<RedicNode> nodes = redic.getRedicNodes();
		
		while(true) {
			
			for(RedicNode node : nodes) {
				node.printJedisInfos();
			}
			
			Thread.sleep(3500);
		}
		
	}
}
