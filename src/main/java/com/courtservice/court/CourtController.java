package com.courtservice.court;

import com.courtservice.common.web.PageResponse;
import com.courtservice.common.web.PaginationDefaults;
import com.courtservice.court.dto.CourtCreateRequest;
import com.courtservice.court.dto.CourtResponse;
import com.courtservice.court.dto.CourtUpdateRequest;
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
 * CRUD endpoints for courts.
 */
@Tag(name = "Courts", description = "Create, read, replace and delete courts")
@RestController
@RequestMapping("/api/courts")
public class CourtController {

    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/ProblemDetail";

    private final CourtService courtService;

    public CourtController(CourtService courtService) {
        this.courtService = courtService;
    }

    @Operation(summary = "Create a court",
            description = "Creates a court and returns it with a Location header pointing to the new resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The court was created.",
                    headers = @Header(name = "Location", description = "URL of the created court."),
                    content = @Content(schema = @Schema(implementation = CourtResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed or has invalid fields.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PostMapping
    public ResponseEntity<CourtResponse> create(@Valid @RequestBody CourtCreateRequest request) {
        CourtResponse response = courtService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get a court", description = "Returns a single court by id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The court was found.",
                    content = @Content(schema = @Schema(implementation = CourtResponse.class))),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No court has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping("/{id}")
    public CourtResponse get(@Parameter(description = "The court id.", example = "1") @PathVariable Long id) {
        return courtService.get(id);
    }

    @Operation(summary = "List courts",
            description = "Returns one page of courts. Defaults to page 0, size 20, sorted by id ascending; "
                    + "size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requested page of courts."),
            @ApiResponse(responseCode = "400", description = "A pagination parameter is invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @GetMapping
    public PageResponse<CourtResponse> list(
            @ParameterObject @PageableDefault(size = PaginationDefaults.DEFAULT_PAGE_SIZE,
                    sort = PaginationDefaults.DEFAULT_SORT_PROPERTY) Pageable pageable) {
        return PageResponse.from(courtService.list(pageable));
    }

    @Operation(summary = "Replace a court", description = "Replaces the name and address of a court.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The court was updated.",
                    content = @Content(schema = @Schema(implementation = CourtResponse.class))),
            @ApiResponse(responseCode = "400", description = "The request body is malformed or has invalid fields.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No court has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @PutMapping("/{id}")
    public CourtResponse update(@Parameter(description = "The court id.", example = "1") @PathVariable Long id,
                                @Valid @RequestBody CourtUpdateRequest request) {
        return courtService.update(id, request);
    }

    @Operation(summary = "Delete a court",
            description = "Deletes a court. A court that still has sessions cannot be deleted.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The court was deleted."),
            @ApiResponse(responseCode = "400", description = "The id is not a valid number.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "404", description = "No court has the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "409", description = "The court still has sessions (RESOURCE_IN_USE).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF))),
            @ApiResponse(responseCode = "500", description = "An unexpected server error occurred.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "The court id.", example = "1") @PathVariable Long id) {
        courtService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
