package cz.upce.fei.nnptp.taskpulse;

import cz.upce.fei.nnptp.taskpulse.domain.Priority;
import cz.upce.fei.nnptp.taskpulse.domain.Project;
import cz.upce.fei.nnptp.taskpulse.domain.Task;
import cz.upce.fei.nnptp.taskpulse.domain.TaskState;
import cz.upce.fei.nnptp.taskpulse.domain.Worklog;
import cz.upce.fei.nnptp.taskpulse.service.ProjectService;
import cz.upce.fei.nnptp.taskpulse.service.TaskService;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryTaskRepository;
import cz.upce.fei.nnptp.taskpulse.storage.ProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.TaskRepository;
import cz.upce.fei.nnptp.taskpulse.storage.IWorkspaceUnitOfWork;
import cz.upce.fei.nnptp.taskpulse.storage.csv.CsvStorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPulseAppTest {

    @TempDir
    Path dataDir;

    @ParameterizedTest
    @ValueSource(strings = { "Programming course", "Fix \"Save as\" dialog", "C:\\Program Files\\new" })
    void oneShotArgumentWithSpecialCharactersStaysOneValue(String name) {
        TaskPulseApp.run(new String[] { "project", "add", name }, dataDir);

        Project project = onlyProject();
        assertThat(project.getName()).isEqualTo(name);
        assertThat(project.getDescription()).isEmpty();
    }

    @Test
    void oneShotKeepsSeparateArgumentsWithSpacesApart() {
        TaskPulseApp.run(new String[] { "project", "add", "Programming course", "Semester work" }, dataDir);

        Project project = onlyProject();
        assertThat(project.getName()).isEqualTo("Programming course");
        assertThat(project.getDescription()).isEqualTo("Semester work");
    }

    @Test
    void oneShotKeepsTitleAndFlagValuesWithSpacesAcrossCommands() {
        TaskPulseApp.run(new String[] { "project", "add", "Course" }, dataDir);
        String projectId = onlyProject().getId().toString();

        TaskPulseApp.run(new String[] { "task", "add", projectId, "Implement CSV escaping" }, dataDir);
        Task task = onlyTask();
        assertThat(task.getTitle()).isEqualTo("Implement CSV escaping");
        
        String taskId = task.getId().toString();
        TaskPulseApp.run(new String[] { "task", "transition", taskId, "IN_PROGRESS" }, dataDir);
        TaskPulseApp.run(new String[] { "worklog", "log", taskId, "45", "--author", "Student Developer", "--desc",
                "First pass" }, dataDir);

        Worklog worklog = onlyTask().getWorklogs().get(0);
        assertThat(worklog.author()).isEqualTo("Student Developer");
        assertThat(worklog.description()).isEqualTo("First pass");
    }

    @ParameterizedTest
    @CsvSource({"y, 0", "n, 1"})
    void oneShotTaskDeleteAsksForConfirmationOnStandardInput(String answer, int remainingTasks) {
        TaskPulseApp.run(new String[]{"project", "add", "Course"}, dataDir);
        String projectId = onlyProject().getId().toString();
        TaskPulseApp.run(new String[]{"task", "add", projectId, "Remove me"}, dataDir);
        String taskId = onlyTask().getId().toString();

        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream((answer + "\n").getBytes(StandardCharsets.UTF_8)));
            TaskPulseApp.run(new String[]{"task", "delete", taskId}, dataDir);
        } finally {
            System.setIn(originalIn);
        }

        try (IWorkspaceUnitOfWork unitOfWork = new CsvStorageManager(dataDir).openUnitOfWork()) {
            assertThat(unitOfWork.tasks().findAll()).hasSize(remainingTasks);
        }
    }

    @Test
    void runDemoExecutesEndToEndWorkflowAndPrintsSummary() {
        ProjectRepository projectRepository = new InMemoryProjectRepository();
        TaskRepository taskRepository = new InMemoryTaskRepository();
        ProjectService projectService = new ProjectService(projectRepository, taskRepository);
        TaskService taskService = new TaskService(taskRepository, projectRepository);

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);

        TaskPulseApp.runDemo(projectService, taskService, out);

        List<Project> projects = projectRepository.findAll();
        assertThat(projects).hasSize(1);
        Project project = projects.get(0);
        assertThat(project.getName()).isEqualTo("TaskPulse Development");
        assertThat(project.getDescription()).isEqualTo("Coursework task tracker");

        List<Task> tasks = taskRepository.findAll();
        assertThat(tasks).hasSize(1);
        Task task = tasks.get(0);
        assertThat(task.getProjectId()).isEqualTo(project.getId());
        assertThat(task.getTitle()).isEqualTo("Implement domain model");
        assertThat(task.getDescription()).isEqualTo("Create Project, Task, Worklog, Priority, State");
        assertThat(task.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(task.getEstimatedMinutes()).isEqualTo(120);
        assertThat(task.getDueDate()).isEqualTo(LocalDate.now().plusDays(2));
        assertThat(task.getLabels()).containsExactlyInAnyOrder("domain", "core");
        assertThat(task.getState()).isEqualTo(TaskState.DONE);
        assertThat(task.getWorklogs()).hasSize(2);
        assertThat(task.getTotalRecordedMinutes()).isEqualTo(105);
        assertThat(task.getVarianceMinutes()).isEqualTo(-15);

        String output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains(
                "=== TaskPulse End-to-End Demo ===",
                "Created Project: TaskPulse Development (" + project.getId() + ")",
                "Created Task: Implement domain model [HIGH]",
                "Transitioning task to IN_PROGRESS...",
                "Logging work...",
                "Recorded minutes: 105",
                "Transitioning task to DONE...",
                "Finished Task: Implement domain model state: DONE, worklogs: 2, variance: -15 min",
                "=== Demo Finished Successfully ===");
    }

    private Project onlyProject() {
        try (IWorkspaceUnitOfWork unitOfWork = new CsvStorageManager(dataDir).openUnitOfWork()) {
            var projects = unitOfWork.projects().findAll();
            assertThat(projects).hasSize(1);
            return projects.get(0);
        }
    }

    private Task onlyTask() {
        try (IWorkspaceUnitOfWork unitOfWork = new CsvStorageManager(dataDir).openUnitOfWork()) {
            var tasks = unitOfWork.tasks().findAll();
            assertThat(tasks).hasSize(1);
            return tasks.get(0);
        }
    }
}
