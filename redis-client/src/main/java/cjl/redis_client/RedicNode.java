package cjl.redis_client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import cjl.redis_client.strategy.RoundRobinSelectStrategy;
import cjl.redis_client.strategy.SelectStrategy;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisShardInfo;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.Hashing;

public class RedicNode {
	
	
	private final Logger log = Logger.getLogger(getClass().getName());

    /**
     * 配置信息
     */
    private GenericObjectPoolConfig poolConfig;

    /**
     * sentinel 监听器，订阅sentinel集群上master变更的消息
     */
    private Set<MasterListener> masterListeners;

    /**
     * 本地master路由表
     * 
     */
    private volatile HostAndPort localMasterRoute = null;
    
    private Map<String,JedisPool> slavePools = new HashMap<String,JedisPool>();

    /**
     * 从sentinel获取master地址出错的重试次数
     */
    private int retrySentinel = 5;
	

	private JedisPool master;
	private List<JedisPool> slaves;

	public SelectStrategy selectStrategy;


	
    public RedicNode(String masterName, List<String> sentinels,
            final GenericObjectPoolConfig poolConfig,int retrySentinel,SelectStrategy selectStrategy) {
        this.poolConfig = poolConfig;
        this.retrySentinel = retrySentinel;
        this.masterListeners = new HashSet<MasterListener>(sentinels.size());

        Map result = initSentinels(sentinels, masterName);
        initMasterPool((HostAndPort)result.get("master"));
        initSlavePools((List<HostAndPort>)result.get("slaves"));
        
        this.selectStrategy = selectStrategy==null?new RoundRobinSelectStrategy():selectStrategy;
    }
	
	public JedisPool getMaster() {
		return master;
	}

	public void setMaster(JedisPool master) {
		this.master = master;
	}

	public List<JedisPool> getSlaves() {
		return slaves;
	}

	public void setSlaves(List<JedisPool> slaves) {
		this.slaves = slaves;
	}

	public JedisPool getRoundRobinSlaveRedicNode() {
		int nodeIndex = selectStrategy.select(slaves.size());

		return slaves.get(nodeIndex);
	}
	
	
    private void initMasterPool(HostAndPort newMasterRoute) {
        if (newMasterRoute != null && !newMasterRoute.equals(this.localMasterRoute)) {
        	this.master = new JedisPool(this.poolConfig,
        			newMasterRoute.getHost(), newMasterRoute.getPort());
        	localMasterRoute = newMasterRoute;
        }
    }
	
    private void initSlavePools(List<HostAndPort> newSlaves) {
    	
    	log.info("begin to initialize slave pool");
    	
    	if(this.slaves == null) {
    		slaves = new ArrayList<JedisPool>();
    	}
    	
    	for(HostAndPort hap : newSlaves) {
    		JedisPool slave = new JedisPool(this.poolConfig, hap.getHost(), hap.getPort());
    		slaves.add(slave);
    		
    		slavePools.put(hap.getHost()+":"+hap.getPort(), slave);
    	}
    	
    	log.info("initialize slave pool ready");
    }
	
	
    /**
     * 初始化Sentinels，获取master路由表信息
     * 
     * @param sentinels
     * @param masters
     * @return
     */
    private Map<String, HostAndPort> initSentinels(List<String> sentinels, String masterName) {

        log.info("Trying to find all master from available Sentinels...");

        HostAndPort MasterRoute = null;
        List<HostAndPort> slaveHaps = new ArrayList<HostAndPort>();

        boolean fetched = false;
        boolean sentinelAvailable = false;
        int sentinelRetryCount = 0;

        while (!fetched && sentinelRetryCount < retrySentinel) {
            for (String sentinel : sentinels) {
                final HostAndPort hap = toHostAndPort(Arrays.asList(sentinel.split(":")));

                log.fine("Connecting to Sentinel " + hap);
                
                Jedis jedis = null;
                try {
                    jedis = new Jedis(hap.getHost(), hap.getPort());
                    // 从sentinel获取masterName当前master-host地址
                    List<String> masterAddr = jedis.sentinelGetMasterAddrByName(masterName);
                    
                    // connected to sentinel...
                    sentinelAvailable = true;

                    if (masterAddr == null || masterAddr.size() != 2) {
                        log.warning("Can not get master addr, master name: " + masterName
                                + ". Sentinel: " + hap + ".");
                        continue;
                    }

                    MasterRoute = toHostAndPort(masterAddr);
                    log.fine("Found Redis master at " + master);
                    
                    
                    //获取 slaves的ip host 
                    List<Map<String, String>> slaves = jedis.sentinelSlaves(masterName);
                    
                    for(Map<String, String> ss : slaves) {		//生成slaves
                    	if(ss.get("master-link-status").equals("ok") && ss.get("flags").equals("slave")) {
                    		List<String> slave = new ArrayList<String>();
                        	slave.add(ss.get("ip"));
                        	slave.add(ss.get("port"));
                        	slaveHaps.add(toHostAndPort(slave));
                    	}
                    }
                    
                    fetched = true;
                    jedis.disconnect();
                    break;
                } catch (JedisConnectionException e) {
                    log.warning("Cannot connect to sentinel running @ " + hap
                            + ". Trying next one.");
                }finally{
                    try{
                        if(jedis != null){
                            jedis.disconnect();
                        }
                    }catch(Exception e1){
                        e1.printStackTrace();
                    }
                }
            }

            if (null == MasterRoute) {
                try {
                    if (sentinelAvailable) {
                        // can connect to sentinel, but master name seems to not
                        // monitored
                        throw new JedisException("Can connect to sentinel, but " + masterName
                                + " seems to be not monitored...");
                    } else {
                        log.severe("All sentinels down, cannot determine where is "
                                + masterName
                                + " master is running... sleeping 1000ms, Will try again.");
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                fetched = false;
                sentinelRetryCount++;
            }
        }

        // Try sentinelRetry times.
        if (!fetched && sentinelRetryCount >= retrySentinel) {
            log.severe("All sentinels down and try " + sentinelRetryCount + " times, Abort.");
            throw new JedisConnectionException("Cannot connect all sentinels, Abort.");
        }

        log.info("Redis master running , starting Sentinel listeners...");
        
        for (String sentinel : sentinels) {
            final HostAndPort hap = toHostAndPort(Arrays.asList(sentinel.split(":")));
            MasterListener masterListener = new MasterListener(masterName, hap.getHost(),
                    hap.getPort());
            // whether MasterListener threads are alive or not, process can be stopped
            masterListener.setDaemon(true);
            masterListeners.add(masterListener);
            masterListener.start();
        }
        
        SlaveListener slaveListener = new SlaveListener(masterName,sentinels);
        slaveListener.setDaemon(true);
        slaveListener.start();
        
        Map result = new HashMap();
        result.put("master", MasterRoute);
        result.put("slaves", slaveHaps);

        return result;
    }
    
    
    /**
     * slave监听器，从sentinel获取slaves的信息并检查更新
     */
    protected class SlaveListener extends Thread {

        protected String masterName;
        protected List<String> sentinels;
        protected long waitTimeMillis = 5000;
        protected AtomicBoolean running = new AtomicBoolean(false);

        protected SlaveListener() {
        }

        public SlaveListener(String masterName, List<String> sentinels) {
            this.masterName = masterName;
            this.sentinels = sentinels;
        }

        public SlaveListener(String masterName, List<String> sentinels, long waitTimeMillis) {
            this(masterName, sentinels);
            this.waitTimeMillis = waitTimeMillis;
        }

        public void run() {
            running.set(true);
            while (running.get()) {
            	
            	for (String sentinel : sentinels) {
                    final HostAndPort hap = toHostAndPort(Arrays.asList(sentinel.split(":")));

                    log.fine("Connecting to Sentinel " + hap);
                    
                    Jedis jedis = null;
                    try {
                        jedis = new Jedis(hap.getHost(), hap.getPort());
                        
                        //获取 slaves的ip host 
                        List<Map<String, String>> slaves = jedis.sentinelSlaves(masterName);
                        
                        jedis.disconnect();
                        
                       Set<String> slaveHaps = new HashSet<String>();
                        
                        for(Map<String, String> ss : slaves) {		//生成slaves
                        	if(ss.get("flags").equals("slave")) {
                            	slaveHaps.add(ss.get("ip")+":"+ss.get("port"));
                        	}
                        }
                        updateSlavePool(slaveHaps);
                        
                        break;
                    } catch (JedisConnectionException e) {
                        log.warning("Cannot connect to sentinel running @ " + hap
                                + ". Trying next one.");
                    }finally{
                        try{
                            if(jedis != null){
                                jedis.disconnect();
                            }
                        }catch(Exception e1){
                            e1.printStackTrace();
                        }
                    }
                }
            	
            	try {
					Thread.sleep(waitTimeMillis);	//休息一下
				} catch (InterruptedException e) {
					e.printStackTrace();
				}	
            	
            }
        }

        public void shutdown() {
            try {
                log.fine("Shutting down slave listener on ");
                running.set(false);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Caught exception while shutting down: ", e);
            }
        }
        
        /**
         * 检查更新
         * @param news
         */
        private void updateSlavePool(Set<String> news) {
        	
        	System.out.println("slaveListener working");
        	
        	Map<String,JedisPool> newPools = new HashMap<String,JedisPool>();
        	
        	Set<String> olds = slavePools.keySet();
        	Set<String> news2 = new HashSet<String>(news);
        	
        	news.retainAll(olds);		//求交集得出还存活的JedisPool无需更新
        	
        	for(String hap : news) {
        		newPools.put(hap, slavePools.get(hap));
        	}
        	
        	news2.removeAll(olds);			//求出新出现的JedisPool
        	
        	if(!news2.isEmpty() || news.size() != olds.size()) {
        		
        		log.info("begin to rebuild slavePool !");
        		
            	for(String hap : news2) {
            		JedisPool slave = new JedisPool(poolConfig, hap.split(":")[0], Integer.parseInt(hap.split(":")[1]));
            		newPools.put(hap, slave);
            	}
            	
            	slavePools.clear();
            	slaves.clear();
            	
            	slavePools = newPools;
            	slaves = new ArrayList(newPools.values());
            	
            	log.info("rebuild slavePool done");
        	}
        }
    }
    
    /**
     * master监听器，从sentinel订阅master变更的消息
     */
    protected class MasterListener extends Thread {

        protected String masterName;
        protected String host;
        protected int port;
        protected long subscribeRetryWaitTimeMillis = 5000;
        protected volatile Jedis j;
        protected AtomicBoolean running = new AtomicBoolean(false);

        protected MasterListener() {
        }

        public MasterListener(String masterName, String host, int port) {
            super(String.format("MasterListener-%s-[%s:%d]", masterName, host, port));
            this.masterName = masterName;
            this.host = host;
            this.port = port;
        }

        public MasterListener(String masterName, String host, int port,
                long subscribeRetryWaitTimeMillis) {
            this(masterName, host, port);
            this.subscribeRetryWaitTimeMillis = subscribeRetryWaitTimeMillis;
        }

        public void run() {
            running.set(true);
            while (running.get()) {
                // Sentinel可能发生宕机，因此try-catch这一步是必须的.
                try {
                    j = new Jedis(host, port);
                    // 订阅master变更消息
                    j.subscribe(new MasterChengeProcessor(this.masterName, this.host, this.port),
                            "+switch-master");
                } catch (JedisConnectionException e) {
                    if (running.get()) {
                        log.severe("Lost connection to Sentinel at " + host + ":" + port
                                + ". Sleeping 5000ms and retrying.");
                        try {
                            Thread.sleep(subscribeRetryWaitTimeMillis);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    } else {
                        log.fine("Unsubscribing from Sentinel at " + host + ":" + port);
                    }
                }
            }
        }

        public void shutdown() {
            try {
                log.fine("Shutting down listener on " + host + ":" + port);
                running.set(false);
                // This isn't good, the Jedis object is not thread safe
                if (j != null) {
                    j.disconnect();
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "Caught exception while shutting down: ", e);
            }
        }
        
    }
	
	
	
    /**
     * 当master变更时接收消息处理
     */
    protected class MasterChengeProcessor extends JedisPubSub {

        protected String masterName;
        protected String host;
        protected int port;

        /**
         * @param masters
         * @param host
         * @param port
         */
        public MasterChengeProcessor(String masterName, String host, int port) {
            super();
            this.masterName = masterName;
            this.host = host;
            this.port = port;
        }

        /*
         * (non-Javadoc)
         * 
         * @see redis.clients.jedis.JedisPubSub#onMessage(java.lang.String, java.lang.String)
         */
        @Override
        public void onMessage(String channel, String message) {
            masterChengeProcessor(channel, message);
        }

        /**
         * master变更消息处理
         */
        private void masterChengeProcessor(String channel, String message) {

            /**
             * message格式：master-name old-master-host old-master-port new-master-host new-master-port
             * <p>
             * 示例：master1 192.168.1.112 6380 192.168.1.111 6379
             */
            log.fine("Sentinel " + host + ":" + port + " published: " + message + ".");
            String[] switchMasterMsg = message.split(" ");
            if (switchMasterMsg.length > 3) {

                String chengeMasterName = switchMasterMsg[0];
                HostAndPort newHostMaster = toHostAndPort(Arrays.asList(switchMasterMsg[3],
                        switchMasterMsg[4]));
                boolean lock = lock(chengeMasterName, newHostMaster);
                try {
                    if (lock) {

                        log.info("Sentinel " + host + ":" + port + " start update...");
                        // 防止二次更新
                        synchronized (MasterChengeProcessor.class) {
                            // 重新初始化pool
                            initMasterPool(newHostMaster);
                        }
                    } else {
                        log.fine("Ignoring message on +switch-master for master name "
                                + switchMasterMsg[0]);
                    }
                } finally {
                    if (lock) {
                        unLock(chengeMasterName, newHostMaster);
                    }
                }
            } else {
                log.severe("Invalid message received on Sentinel " + host + ":" + port
                        + " on channel +switch-master: " + message);
            }
        }

        /**
         * master变更时初始化连接池更新锁
         */
        private  ConcurrentHashMap<String, HostAndPort> updatePoolLock = new ConcurrentHashMap<String, HostAndPort>();

        
        
        /**
         * 1.因sentinel集群能同时管理多组master-slave,故只处理当前工程配置的master变更
         * <p>
         * 2.如果变更的master信息已存在，并且对应ip一致，则为重复消息（放弃更新）
         * <p>
         * 3.如果变更的master信息已存在，不一致则为master变更（lock）
         * <p>
         * 
         * @param chengeMasterName 变更的master-name
         * @param newHostMaster 新的master地址
         * @return
         */
        private boolean lock(String chengeMasterName, HostAndPort newHostMaster) {
        	
            if (!masterName.equals(chengeMasterName)) {
                return false;
            }

            if (newHostMaster.equals(localMasterRoute)) {
                log.info("Sentinel " + host + ":" + port + " update " + chengeMasterName
                        + " failure! because Has been updated.");
                return false;
            }

            String key = String.format("%s-%s", chengeMasterName, newHostMaster);
            HostAndPort putIfAbsent = updatePoolLock.putIfAbsent(key, newHostMaster);
            if (null != putIfAbsent && newHostMaster.equals(putIfAbsent)) {
                log.info("Sentinel " + host + ":" + port + " lock " + chengeMasterName
                        + " failure! because Has been lock.");
                return false;
            }

            log.info("Sentinel " + host + ":" + port + " lock " + chengeMasterName
                    + " success! key:" + key);
            return true;
        }

        /**
         * 解除锁定
         * 
         * @param chengeMasterName
         * @param newHostMaster
         */
        private void unLock(String chengeMasterName, HostAndPort newHostMaster) {
            String key = String.format("%s-%s", chengeMasterName, newHostMaster);
            updatePoolLock.remove(key);
            log.info("Sentinel " + host + ":" + port + " unlock " + chengeMasterName + " success.");
        }
    }
	
	
    private HostAndPort toHostAndPort(List<String> masterAddr) {
        String host = masterAddr.get(0);
        int port = Integer.parseInt(masterAddr.get(1));
        return new HostAndPort(host, port);
    }
    
    
    public void printJedisInfos() {
    
    	System.out.println("-----------------------");
    	
    	System.out.println("master is : "+ localMasterRoute.getHost()+":"+localMasterRoute.getPort());
    	
    	for(String hap : slavePools.keySet()) {
    		System.out.println("slave is : " + hap);
    	}
    }
	
}
