package com.courtservice.member;

import com.courtservice.common.web.PageResponse;
import com.courtservice.common.web.PaginationDefaults;
import com.courtservice.member.dto.MemberCreateRequest;
import com.courtservice.member.dto.MemberResponse;
import com.courtservice.member.dto.MemberUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * CRUD endpoints for members.
 */
@Tag(name = "Members", description = "Create, read, replace and delete members")
@RestController
@RequestMapping("/api/members")
public class MemberController {

    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/ProblemDetail";

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @Operation(summary = "Create a member",
            description = "Creates a member and returns it with a Location header pointing to the new resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The member was created.",
                    headers = @Header(name = "Location", description = "URL of the created member."),
                    content = @Content(schema = @Schema(implementation = MemberResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed or has invalid fields.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PostMapping
    public ResponseEntity<MemberResponse> create(@Valid @RequestBody MemberCreateRequest request) {
        MemberResponse response = memberService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get a member", description = "Returns a single member by id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The member was found.",
                    content = @Content(schema = @Schema(implementation = MemberResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No member has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/{id}")
    public MemberResponse get(@Parameter(description = "The member id.", example = "1") @PathVariable Long id) {
        return memberService.get(id);
    }

    @Operation(summary = "List members",
            description = "Returns one page of members. Defaults to page 0, size 20, sorted by id ascending; "
                    + "size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requested page of members."),
            @ApiResponse(responseCode = "400", description = "A pagination parameter is invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping
    public PageResponse<MemberResponse> list(
            @ParameterObject @PageableDefault(size = PaginationDefaults.DEFAULT_PAGE_SIZE,
                    sort = PaginationDefaults.DEFAULT_SORT_PROPERTY) Pageable pageable) {
        return PageResponse.from(memberService.list(pageable));
    }

    @Operation(summary = "Replace a member", description = "Replaces the name of a member.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The member was updated.",
                    content = @Content(schema = @Schema(implementation = MemberResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed or has invalid fields.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No member has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PutMapping("/{id}")
    public MemberResponse update(@Parameter(description = "The member id.", example = "1") @PathVariable Long id,
                                @Valid @RequestBody MemberUpdateRequest request) {
        return memberService.update(id, request);
    }

    @Operation(summary = "Delete a member",
            description = "Deletes a member. A member that still has bookings cannot be deleted.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The member was deleted."),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No member has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "409", description = "The member still has bookings (RESOURCE_IN_USE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "The member id.", example = "1") @PathVariable Long id) {
        memberService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
