package fun.hatsumi.chatbackend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import fun.hatsumi.chatbackend.user.entity.UserEntity;

/**
 * 用户表 Mapper（单表等值查询走 LambdaQueryWrapper，无需 XML）。
 */
public interface UserMapper extends BaseMapper<UserEntity> {
}
