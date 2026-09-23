package com.courtservice.member;

import com.courtservice.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Member extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    public Member(String name) {
        this.name = name;
    }
}
