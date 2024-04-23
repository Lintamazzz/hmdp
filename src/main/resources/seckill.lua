-- 参数列表
-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]

-- key
-- 库存 string
local stockKey = 'seckill:stock:' .. voucherId
-- 已下单的用户 set
local orderKey = 'seckill:order:' .. voucherId


-- 脚本业务开始
-- 1. 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回 1
    return 1
end
-- 2. 判断用户是否已下单，保证一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户id存在于set，说明重复下单，返回 2
    return 2
end
-- 3. 库存充足、且没有下单过
-- 扣库存
redis.call('incrby', stockKey, -1)
-- 保存用户
redis.call('sadd', orderKey, userId)
-- 秒杀成功，返回 0
return 0