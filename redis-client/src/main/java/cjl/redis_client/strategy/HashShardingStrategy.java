package cjl.redis_client.strategy;

public class HashShardingStrategy implements ShardingStrategy {
	public <T> int key2node(T key, int nodeCount) {
		int hashCode = key.hashCode();
		return hashCode % nodeCount;
	}
}
