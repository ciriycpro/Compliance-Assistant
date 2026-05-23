package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);

    private final ClientRepository repository;

    public ClientController(ClientRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientCreateRequest request) {
        if (repository.existsByInn(request.inn())) {
            log.warn("client with inn={} already exists", request.inn());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Client client = new Client();
        client.setInn(request.inn());
        client.setFullName(request.fullName());
        client.setPhone(request.phone());
        client.setStatus(ClientStatus.ACTIVE);

        Client saved = repository.save(client);
        log.info("client created id={} inn={}", saved.getId(), saved.getInn());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClientResponse> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(client -> ResponseEntity.ok(toResponse(client)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ClientResponse toResponse(Client c) {
        return new ClientResponse(
                c.getId(),
                c.getInn(),
                c.getFullName(),
                c.getPhone(),
                c.getStatus().name(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public record ClientCreateRequest(
            @NotBlank @Size(min = 10, max = 12) String inn,
            @NotBlank String fullName,
            String phone
    ) {}

    public record ClientResponse(
            UUID id,
            String inn,
            String fullName,
            String phone,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
