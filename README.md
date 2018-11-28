# redis-client
redis集群客户端：封装jedis，支持读写分离，sentinel监听，集群分片

基于github上的redic项目和redis-sentinel-client项目的改进，合并了这两个项目，提供：

1.读写分离
2.sentinel监听master故障切换
3.线程通过sentinel监控slaves状态，定时检查并切换
4.集群分片，可自定义分片算法，默认hash
