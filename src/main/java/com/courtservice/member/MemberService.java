package com.courtservice.member;

import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.member.dto.MemberCreateRequest;
import com.courtservice.member.dto.MemberResponse;
import com.courtservice.member.dto.MemberUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages members. Owns the member module's persistence; other modules reach members only
 * through this service.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class MemberService {

    private static final String RESOURCE_NAME = "Member";

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * Creates a member.
     *
     * @param request the member details
     * @return the created member
     */
    @Transactional
    public MemberResponse create(MemberCreateRequest request) {
        Member member = memberRepository.save(new Member(request.name()));
        log.info("Created member {}", member.getId());
        return toResponse(member);
    }

    /**
     * Returns a single member.
     *
     * @param id the member id
     * @return the member
     * @throws ResourceNotFoundException if no member has the given id
     */
    public MemberResponse get(Long id) {
        return toResponse(findMember(id));
    }

    /**
     * Returns one page of members.
     *
     * @param pageable the requested page, size and sort
     * @return the requested page
     */
    public Page<MemberResponse> list(Pageable pageable) {
        return memberRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Replaces the details of a member.
     *
     * @param id      the member id
     * @param request the new details
     * @return the updated member
     * @throws ResourceNotFoundException if no member has the given id
     */
    @Transactional
    public MemberResponse update(Long id, MemberUpdateRequest request) {
        Member member = findMember(id);
        member.rename(request.name());
        // Flush before mapping so that @UpdateTimestamp has already refreshed updatedAt.
        memberRepository.flush();
        log.info("Updated member {}", id);
        return toResponse(member);
    }

    /**
     * Deletes a member.
     *
     * <p>Whether the member still has bookings is decided by the {@code booking.member_id}
     * foreign key rather than by asking the booking module, because the booking module already
     * depends on this one and a reverse call would create a cycle. The flush forces the
     * constraint check inside this method, so the resulting
     * {@link org.springframework.dao.DataIntegrityViolationException} is raised here and mapped
     * to {@code RESOURCE_IN_USE} by the global exception handler.
     *
     * @param id the member id
     * @throws ResourceNotFoundException if no member has the given id
     * @throws org.springframework.dao.DataIntegrityViolationException if the member still has bookings
     */
    @Transactional
    public void delete(Long id) {
        memberRepository.delete(findMember(id));
        memberRepository.flush();
        log.info("Deleted member {}", id);
    }

    /**
     * Verifies that a member exists, for other modules that query data owned by a member.
     *
     * @param id the member id
     * @throws ResourceNotFoundException if no member has the given id
     */
    public void verifyExists(Long id) {
        if (!memberRepository.existsById(id)) {
            throw new ResourceNotFoundException(RESOURCE_NAME, id);
        }
    }

    private Member findMember(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
    }

    private MemberResponse toResponse(Member member) {
        return new MemberResponse(member.getId(), member.getName(), member.getCreatedAt(), member.getUpdatedAt());
    }
}
