package cjl.Utils;

import java.util.Set;
import java.lang.reflect.Type;
import java.util.UUID;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import redis.clients.jedis.Jedis;

public class RedisDelayingQueue<T> {

	  static class TaskItem<T> {
	    public String id;
	    public T msg;
	  }

	  // fastjson 序列化对象中存在 generic 类型时，需要使用 TypeReference
	  private Type TaskType = new TypeReference<TaskItem<T>>() {
	  }.getType();

	  private Jedis jedis;
	  private String queueKey;

	  public RedisDelayingQueue(Jedis jedis, String queueKey) {
	    this.jedis = jedis;
	    this.queueKey = queueKey;
	  }

	  public void delay(T msg) {
	    TaskItem<T> task = new TaskItem<T>();
	    task.id = UUID.randomUUID().toString(); // 分配唯一的 uuid
	    task.msg = msg;
	    String s = JSON.toJSONString(task); // fastjson 序列化
	    jedis.zadd(queueKey, System.currentTimeMillis() + 5000, s); // 塞入延时队列 ,5s 后再试
	  }

	  public void loop() {
	    while (!Thread.interrupted()) {
	      // 只取一条
	      Set<String> values = jedis.zrangeByScore(queueKey, 0, System.currentTimeMillis(), 0, 1);
	      if (values.isEmpty()) {
	        try {
	          Thread.sleep(500); // 歇会继续
	        } catch (InterruptedException e) {
	          break;
	        }
	        continue;
	      }
	      String s = values.iterator().next();
	      if (jedis.zrem(queueKey, s) > 0) { // 抢到了
	        TaskItem<T> task = JSON.parseObject(s, TaskType); // fastjson 反序列化
	        this.handleMsg(task.msg);
	      }
	    }
	  }
	  public void handleMsg(T msg) {
	    System.out.println(msg);
	  }

	  public static void main(String[] args) {
		  
	    Jedis jedis = new Jedis("172.16.161.161",6379);
	    
	    final RedisDelayingQueue<String> queue = new RedisDelayingQueue<String>(jedis, "q-demo");
	    
	    Thread producer = new Thread() {

	      public void run() {
	        for (int i = 0; i < 10; i++) {
	          queue.delay("codehole" + i);
	        }
	      }

	    };
	    Thread consumer = new Thread() {

	      public void run() {
	        queue.loop();
	      }

	    };
	    producer.start();
	    consumer.start();
	    try {
	      producer.join();
	      System.out.println("join done");
//	      Thread.sleep(6000);
	      consumer.interrupt();
	      consumer.join();
	    } catch (InterruptedException e) {
	    }
	  }
	}

	      
	 