package com.courtservice.court;

import com.courtservice.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A physical court that hosts court sessions.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Court extends BaseEntity {

    /** Maximum length of {@code name}, matching the {@code court.name} column. */
    public static final int NAME_MAX_LENGTH = 100;

    /** Maximum length of {@code address}, matching the {@code court.address} column. */
    public static final int ADDRESS_MAX_LENGTH = 255;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(length = ADDRESS_MAX_LENGTH)
    private String address;

    public Court(String name, String address) {
        this.name = name;
        this.address = address;
    }

    /**
     * Replaces the descriptive details of this court.
     *
     * @param name    the new name
     * @param address the new address, or {@code null} to clear it
     */
    public void changeDetails(String name, String address) {
        this.name = name;
        this.address = address;
    }
}
