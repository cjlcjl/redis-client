package cjl.redis_client.strategy;

public interface SelectStrategy {
	public int select(int count);
}
