package com.courtservice.member;

import com.courtservice.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A member who can book court sessions.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Member extends BaseEntity {

    /** Maximum length of {@code name}, matching the {@code member.name} column. */
    public static final int NAME_MAX_LENGTH = 100;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    public Member(String name) {
        this.name = name;
    }

    /**
     * Changes the name of this member.
     *
     * @param name the new name
     */
    public void rename(String name) {
        this.name = name;
    }
}
