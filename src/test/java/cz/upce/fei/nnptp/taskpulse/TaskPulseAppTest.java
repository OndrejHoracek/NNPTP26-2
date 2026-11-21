package cz.upce.fei.nnptp.taskpulse;

import cz.upce.fei.nnptp.taskpulse.domain.Project;
import cz.upce.fei.nnptp.taskpulse.domain.Task;
import cz.upce.fei.nnptp.taskpulse.domain.Worklog;
import cz.upce.fei.nnptp.taskpulse.storage.csv.CsvStorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPulseAppTest {

    @TempDir
    Path dataDir;

    @ParameterizedTest
    @ValueSource(strings = {"Programming course", "Fix \"Save as\" dialog", "C:\\Program Files\\new"})
    void oneShotArgumentWithSpecialCharactersStaysOneValue(String name) {
        TaskPulseApp.run(new String[]{"project", "add", name}, dataDir);

        Project project = onlyProject();
        assertThat(project.getName()).isEqualTo(name);
        assertThat(project.getDescription()).isEmpty();
    }

    @Test
    void oneShotKeepsSeparateArgumentsWithSpacesApart() {
        TaskPulseApp.run(new String[]{"project", "add", "Programming course", "Semester work"}, dataDir);

        Project project = onlyProject();
        assertThat(project.getName()).isEqualTo("Programming course");
        assertThat(project.getDescription()).isEqualTo("Semester work");
    }

    @Test
    void oneShotKeepsTitleAndFlagValuesWithSpacesAcrossCommands() {
        TaskPulseApp.run(new String[]{"project", "add", "Course"}, dataDir);
        String projectId = onlyProject().getId().toString();

        TaskPulseApp.run(new String[]{"task", "add", projectId, "Implement CSV escaping"}, dataDir);
        Task task = onlyTask();
        assertThat(task.getTitle()).isEqualTo("Implement CSV escaping");

        String taskId = task.getId().toString();
        TaskPulseApp.run(new String[]{"task", "transition", taskId, "IN_PROGRESS"}, dataDir);
        TaskPulseApp.run(new String[]{"worklog", "log", taskId, "45", "--author", "Student Developer", "--desc", "First pass"}, dataDir);

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

        assertThat(new CsvStorageManager(dataDir).load().taskRepository().findAll()).hasSize(remainingTasks);
    }

    private Project onlyProject() {
        var projects = new CsvStorageManager(dataDir).load().projectRepository().findAll();
        assertThat(projects).hasSize(1);
        return projects.get(0);
    }

    private Task onlyTask() {
        var tasks = new CsvStorageManager(dataDir).load().taskRepository().findAll();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }
}
