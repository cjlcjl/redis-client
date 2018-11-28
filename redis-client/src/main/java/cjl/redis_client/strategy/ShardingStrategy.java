package cjl.redis_client.strategy;

public interface ShardingStrategy {
	public <T> int key2node(T key, int nodeCount);
}
