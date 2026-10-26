package com.trs.app;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
/** 用户持久化接口。 @author agent */
public interface UserRepository extends JpaRepository<UserAccount, Long> {
    /** 按用户名查找账户。 @param username 用户名 @return 账户 @author agent */
    Optional<UserAccount> findByUsername(String username);
}
