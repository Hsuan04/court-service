package com.courtservice.booking.controller;

import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.dto.BookingCreateResponse;
import com.courtservice.booking.dto.BookingResponse;
import com.courtservice.common.web.PageResponse;
import com.courtservice.common.web.PaginationDefaults;
import com.courtservice.session.CourtSessionService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Read-only endpoints for bookings, including the booking sub-resources of sessions and members.
 */
@Tag(name = "Bookings", description = "Query bookings")
@RestController
public class BookingController {

    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/ProblemDetail";

    private final BookingService bookingService;
    private final CourtSessionService courtSessionService;

    public BookingController(BookingService bookingService, CourtSessionService courtSessionService) {
        this.bookingService = bookingService;
        this.courtSessionService = courtSessionService;
    }

    @Operation(summary = "Create a booking",
            description = "Books one seat of a court session for a member, using the concurrency strategy "
                    + "configured by app.booking.concurrency-mode.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The booking was created.",
                    headers = @Header(name = "Location", description = "URL of the created booking."),
                    content = @Content(schema = @Schema(implementation = BookingCreateResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed or has invalid "
                    + "fields (VALIDATION_FAILED).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No session or member has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "409", description = "The session is full (SESSION_FULL), the member "
                    + "has already booked it (DUPLICATE_BOOKING), or too many bookings of the session are in "
                    + "progress and the request may be retried (LOCK_TIMEOUT).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PostMapping("/api/bookings")
    public ResponseEntity<BookingCreateResponse> create(@Valid @RequestBody BookingCreateRequest request) {
        BookingCreateResponse response = bookingService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.booking().id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get a booking", description = "Returns a single booking by id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The booking was found.",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No booking has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/api/bookings/{id}")
    public BookingResponse get(@Parameter(description = "The booking id.", example = "1") @PathVariable Long id) {
        return bookingService.get(id);
    }

    @Operation(summary = "List bookings of a court session",
            description = "Returns one page of bookings of a session. Defaults to page 0, size 20, sorted by id "
                    + "ascending; size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requested page of bookings."),
            @ApiResponse(responseCode = "400", description = "The id or a pagination parameter is invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No session has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/api/court-sessions/{id}/bookings")
    public PageResponse<BookingResponse> listByCourtSession(
            @Parameter(description = "The session id.", example = "1") @PathVariable Long id,
            @ParameterObject @PageableDefault(size = PaginationDefaults.DEFAULT_PAGE_SIZE,
                    sort = PaginationDefaults.DEFAULT_SORT_PROPERTY) Pageable pageable) {
        return PageResponse.from(bookingService.listByCourtSession(id, pageable));
    }

    @Operation(summary = "List bookings of a member",
            description = "Returns one page of bookings of a member. Defaults to page 0, size 20, sorted by id "
                    + "ascending; size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requested page of bookings."),
            @ApiResponse(responseCode = "400", description = "The id or a pagination parameter is invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No member has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/api/members/{id}/bookings")
    public PageResponse<BookingResponse> listByMember(
            @Parameter(description = "The member id.", example = "1") @PathVariable Long id,
            @ParameterObject @PageableDefault(size = PaginationDefaults.DEFAULT_PAGE_SIZE,
                    sort = PaginationDefaults.DEFAULT_SORT_PROPERTY) Pageable pageable) {
        return PageResponse.from(bookingService.listByMember(id, pageable));
    }

}
