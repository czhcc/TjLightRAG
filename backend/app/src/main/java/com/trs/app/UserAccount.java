package com.trs.app;

import jakarta.persistence.*;
import lombok.*;

/** 登录用户实体。 @author agent */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 100)
  private String username;

  @Column(name = "is_admin", nullable = false)
  private boolean admin;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;
}
