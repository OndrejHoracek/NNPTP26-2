package cz.upce.fei.nnptp.taskpulse.storage.csv;

import cz.upce.fei.nnptp.taskpulse.domain.Project;
import cz.upce.fei.nnptp.taskpulse.domain.Task;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryTaskRepository;
import cz.upce.fei.nnptp.taskpulse.storage.ProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.StorageException;
import cz.upce.fei.nnptp.taskpulse.storage.TaskRepository;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persists a workspace as CSV files. Row conversion is delegated to {@link CsvEntityMapper} implementations;
 * this class is responsible for file I/O and for validating relationships between entities.
 */
public class CsvStorageManager {
    private static final String UTF8_BOM = "\uFEFF";

    private final Path dataDirectory;
    private final ProjectCsvMapper projectMapper = new ProjectCsvMapper();
    private final TaskCsvMapper taskMapper = new TaskCsvMapper();
    private final WorklogCsvMapper worklogMapper = new WorklogCsvMapper();

    public CsvStorageManager(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "Data directory must not be null");
    }

    public record LoadedWorkspace(ProjectRepository projectRepository, TaskRepository taskRepository) {}

    public LoadedWorkspace load() {
        InMemoryProjectRepository projectRepo = new InMemoryProjectRepository();
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository();

        if (!Files.exists(dataDirectory)) {
            return new LoadedWorkspace(projectRepo, taskRepo);
        }

        Set<UUID> projectIds = new HashSet<>();
        readAll(projectMapper, project -> {
            if (!projectIds.add(project.getId())) {
                throw new StorageException("Duplicate project id detected: " + project.getId());
            }
            projectRepo.save(project);
        });

        Map<UUID, Task> tasksById = new HashMap<>();
        readAll(taskMapper, task -> {
            if (!projectIds.contains(task.getProjectId())) {
                throw new StorageException("Task references missing project: " + task.getProjectId());
            }
            if (tasksById.putIfAbsent(task.getId(), task) != null) {
                throw new StorageException("Duplicate task id detected: " + task.getId());
            }
            taskRepo.save(task);
        });

        readAll(worklogMapper, taskWorklog -> {
            Task task = tasksById.get(taskWorklog.taskId());
            if (task == null) {
                throw new StorageException("Worklog references missing task: " + taskWorklog.taskId());
            }
            task.restoreWorklog(taskWorklog.worklog());
        });

        return new LoadedWorkspace(projectRepo, taskRepo);
    }

    public void save(ProjectRepository projectRepository, TaskRepository taskRepository) {
        List<Project> projects = projectRepository.findAll();
        List<Task> tasks = taskRepository.findAll();
        Iterable<WorklogCsvMapper.TaskWorklog> worklogs = () -> tasks.stream()
                .flatMap(task -> task.getWorklogs().stream()
                        .map(worklog -> new WorklogCsvMapper.TaskWorklog(task.getId(), worklog)))
                .iterator();

        try {
            Files.createDirectories(dataDirectory);
            writeAll(projectMapper, projects);
            writeAll(taskMapper, tasks);
            writeAll(worklogMapper, worklogs);
        } catch (IOException e) {
            throw new StorageException("Failed to save workspace to CSV: " + e.getMessage(), e);
        }
    }

    private <T> void readAll(CsvEntityMapper<T> mapper, Consumer<T> consumer) {
        Path path = dataDirectory.resolve(mapper.fileName());
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != UTF8_BOM.charAt(0)) {
                reader.reset();
            }
            List<List<String>> rows = CsvUtils.parse(reader);
            if (!rows.isEmpty()) {
                validateHeader(mapper, rows.get(0));
            }
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (isBlankRow(row)) {
                    continue;
                }
                CsvRecord record = new CsvRecord(mapper.fileName(), mapper.columns(), row, i + 1);
                consumer.accept(mapper.fromRecord(record));
            }
        } catch (Exception e) {
            throw new StorageException("Failed to load " + mapper.fileName() + ": " + e.getMessage(), e);
        }
    }

    private static void validateHeader(CsvEntityMapper<?> mapper, List<String> header) {
        List<String> actual = header.stream()
                .map(column -> column.replace(UTF8_BOM, "").strip())
                .toList();
        if (!actual.equals(mapper.columns())) {
            throw new StorageException("Unsupported header in " + mapper.fileName()
                    + ": expected '" + String.join(",", mapper.columns())
                    + "' but found '" + String.join(",", header) + "'");
        }
    }

    private <T> void writeAll(CsvEntityMapper<T> mapper, Iterable<T> values) throws IOException {
        Path target = dataDirectory.resolve(mapper.fileName());
        Path temporary = Files.createTempFile(
                dataDirectory,
                "csv-",
                ".tmp");
        try {
            writeCsv(temporary, mapper, values);

            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <T> void writeCsv(Path path, CsvEntityMapper<T> mapper, Iterable<T> values) throws IOException {
        try (BufferedWriter bufferedWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeLine(bufferedWriter, mapper.columns());

            for (T value : values) {
                writeLine(bufferedWriter, mapper.toFields(value));
            }
        }
    }

    private static void writeLine(BufferedWriter writer, List<String> fields) throws IOException {
        writer.write(CsvUtils.formatRow(fields));
        writer.write('\n');
    }

    private static boolean isBlankRow(List<String> row) {
        return row.isEmpty() || (row.size() == 1 && row.get(0).isBlank());
    }
}
