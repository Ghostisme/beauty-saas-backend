package com.beauty.saas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beauty.saas.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户Mapper接口
 *
 * @author Beauty SaaS Team
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
