-- 1.参数列表
-- 1.1 优惠卷id
local voucherId = ARGV[1] -- 是key的一部分，不是key用ARGV接收
-- 1.2 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId -- ..为拼接符
-- 2.2 订单key(哪些用户购买了这个秒杀卷)
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2 库存不足，返回1
    return 1
end
-- 3.2 判断用户是否已购买过(即判断key对应的value(set集合)中有没有当前用户id) SISMEMBER orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3 存在，是重复下单，返回2
    return 2
end
-- 3.4 扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5 下单(保存用户) sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6 发送消息到队列中 XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0