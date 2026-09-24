package com.courtservice.session;

import com.courtservice.common.web.PageResponse;
import com.courtservice.common.web.PaginationDefaults;
import com.courtservice.session.dto.CourtSessionCreateRequest;
import com.courtservice.session.dto.CourtSessionResponse;
import com.courtservice.session.dto.CourtSessionUpdateRequest;
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
 * CRUD endpoints for court sessions. {@code bookedCount} is exposed read-only and can only be
 * changed by the booking flow.
 */
@Tag(name = "Court Sessions", description = "Create, read, replace and delete court sessions")
@RestController
@RequestMapping("/api/court-sessions")
public class CourtSessionController {

    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/ProblemDetail";

    private final CourtSessionService courtSessionService;

    public CourtSessionController(CourtSessionService courtSessionService) {
        this.courtSessionService = courtSessionService;
    }

    @Operation(summary = "Create a court session",
            description = "Creates a session on an existing court with bookedCount 0 and returns it with a "
                    + "Location header. endTime must be after startTime and closeAt must be after openAt.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The session was created.",
                    headers = @Header(name = "Location", description = "URL of the created session."),
                    content = @Content(schema = @Schema(implementation = CourtSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed, has invalid fields "
                    + "(VALIDATION_FAILED) or has an invalid time range (INVALID_TIME_RANGE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No court has the given courtId.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PostMapping
    public ResponseEntity<CourtSessionResponse> create(@Valid @RequestBody CourtSessionCreateRequest request) {
        CourtSessionResponse response = courtSessionService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get a court session", description = "Returns a single court session by id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The session was found.",
                    content = @Content(schema = @Schema(implementation = CourtSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No session has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/{id}")
    public CourtSessionResponse get(@Parameter(description = "The session id.", example = "1") @PathVariable Long id) {
        return courtSessionService.get(id);
    }

    @Operation(summary = "List court sessions",
            description = "Returns one page of court sessions. Defaults to page 0, size 20, sorted by id "
                    + "ascending; size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requested page of sessions."),
            @ApiResponse(responseCode = "400", description = "A pagination parameter is invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping
    public PageResponse<CourtSessionResponse> list(
            @ParameterObject @PageableDefault(size = PaginationDefaults.DEFAULT_PAGE_SIZE,
                    sort = PaginationDefaults.DEFAULT_SORT_PROPERTY) Pageable pageable) {
        return PageResponse.from(courtSessionService.list(pageable));
    }

    @Operation(summary = "Replace a court session",
            description = "Replaces the date, time slot, capacity and booking window of a session. The hosting "
                    + "court cannot be changed, and bookedCount is ignored if sent. Capacity cannot be reduced "
                    + "below the current bookedCount.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The session was updated.",
                    content = @Content(schema = @Schema(implementation = CourtSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed, has invalid fields "
                    + "(VALIDATION_FAILED) or has an invalid time range (INVALID_TIME_RANGE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No session has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "409", description = "The capacity is less than the current bookedCount "
                    + "(BUSINESS_RULE_VIOLATION), or the session was modified concurrently "
                    + "(CONCURRENT_MODIFICATION).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PutMapping("/{id}")
    public CourtSessionResponse update(@Parameter(description = "The session id.", example = "1") @PathVariable Long id,
                                       @Valid @RequestBody CourtSessionUpdateRequest request) {
        return courtSessionService.update(id, request);
    }

    @Operation(summary = "Delete a court session",
            description = "Deletes a court session. A session that still has bookings cannot be deleted.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The session was deleted."),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No session has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "409", description = "The session still has bookings (RESOURCE_IN_USE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "The session id.", example = "1") @PathVariable Long id) {
        courtSessionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
