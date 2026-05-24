package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.ciriycpro.compliance.service.ClientService;

import java.time.Instant;
import java.util.UUID;

@RestController
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @PostMapping("/clients")
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientCreateRequest req) {
        Client saved = clientService.create(req.inn(), req.fullName(), req.phone());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/clients/{id}")
    public ResponseEntity<ClientResponse> getById(@PathVariable UUID id) {
        return clientService.findById(id)
                .map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ClientResponse toResponse(Client c) {
        return new ClientResponse(
                c.getId(), c.getInn(), c.getFullName(), c.getPhone(),
                c.getStatus().name(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    public record ClientCreateRequest(
            @NotBlank @Size(min = 10, max = 12) String inn,
            @NotBlank String fullName,
            String phone
    ) {}

    public record ClientResponse(
            UUID id, String inn, String fullName, String phone,
            String status, Instant createdAt, Instant updatedAt
    ) {}
}
