package com.filenest.core.log;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filenest.model.ActionType;
import com.filenest.model.OperationBatch;
import com.filenest.model.OperationRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON-file backed {@link OperationLog}. Deliberately lightweight — a personal, single-user
 * tool does not need a database; a small JSON file is enough to make undo durable.
 *
 * <p>Paths are stored as plain strings (not Jackson's structural Path form) so the file is
 * human-readable and portable. All access is synchronized because the UI may append from a
 * background thread while reading history on the FX thread.
 */
public final class JsonOperationLog implements OperationLog {

    private final Path file;
    private final ObjectMapper mapper;
    private final List<OperationBatch> batches = new ArrayList<>();

    public JsonOperationLog(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    /** Default location under the user's home directory. */
    public static JsonOperationLog atDefaultLocation() {
        Path dir = Path.of(System.getProperty("user.home"), ".filenest");
        return new JsonOperationLog(dir.resolve("operations.json"));
    }

    @Override
    public synchronized void append(OperationBatch batch) {
        batches.add(batch);
        save();
    }

    @Override
    public synchronized List<OperationBatch> history() {
        return List.copyOf(batches);
    }

    @Override
    public synchronized Optional<OperationBatch> last() {
        return batches.isEmpty() ? Optional.empty() : Optional.of(batches.get(batches.size() - 1));
    }

    @Override
    public synchronized Optional<OperationBatch> find(String batchId) {
        return batches.stream().filter(b -> b.id().equals(batchId)).findFirst();
    }

    @Override
    public synchronized void remove(String batchId) {
        if (batches.removeIf(b -> b.id().equals(batchId))) {
            save();
        }
    }

    // ---- persistence ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<BatchDto> dtos = mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<List<BatchDto>>() {
                    });
            for (BatchDto dto : dtos) {
                batches.add(dto.toModel());
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt/unreadable log must not crash the app; we start empty but keep the file.
            System.err.println("[Log] could not read " + file + ": " + e.getMessage());
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<BatchDto> dtos = batches.stream().map(BatchDto::from).toList();
            mapper.writeValue(file.toFile(), dtos);
        } catch (IOException e) {
            System.err.println("[Log] could not write " + file + ": " + e.getMessage());
        }
    }

    // ---- JSON DTOs (keep java.nio Path out of Jackson) --------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class BatchDto {
        public String id;
        public Instant executedAt;
        public List<RecordDto> records = new ArrayList<>();

        static BatchDto from(OperationBatch b) {
            BatchDto dto = new BatchDto();
            dto.id = b.id();
            dto.executedAt = b.executedAt();
            dto.records = b.records().stream().map(RecordDto::from).toList();
            return dto;
        }

        OperationBatch toModel() {
            List<OperationRecord> recs = records.stream().map(RecordDto::toModel).toList();
            return new OperationBatch(id, executedAt, recs);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RecordDto {
        public String from;
        public String to;
        public String type;

        static RecordDto from(OperationRecord r) {
            RecordDto dto = new RecordDto();
            dto.from = r.from().toString();
            dto.to = r.to().toString();
            dto.type = r.type().name();
            return dto;
        }

        OperationRecord toModel() {
            return new OperationRecord(Path.of(from), Path.of(to), ActionType.valueOf(type));
        }
    }
}
