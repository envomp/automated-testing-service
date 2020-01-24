package ee.taltech.arete.component;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import ee.taltech.arete.service.docker.ImageCheck;
import ee.taltech.arete.service.git.GitPullService;
import ee.taltech.arete.service.queue.PriorityQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class ApplicationStartup implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ApplicationStartup.class);

	@Autowired
	private GitPullService gitPullService;

	@Autowired
	private PriorityQueueService priorityQueueService;

	@Override
	public void run(ApplicationArguments applicationArguments) throws Exception {
		log.info("setting up temp folders.");

		createDirectory("input_and_output");
		createDirectory("students");
		createDirectory("tests");

		for (int i = 0; i < 16; i++) {

			createDirectory(String.format("input_and_output/%s", i));
			createDirectory(String.format("input_and_output/%s/tester", i));
			createDirectory(String.format("input_and_output/%s/student", i));
			createDirectory(String.format("input_and_output/%s/host", i));

			try {
				new File(String.format("input_and_output/%s/host/input.json", i)).createNewFile();
			} catch (Exception ignored) {
			}

			try {
				new File(String.format("input_and_output/%s/host/output.json", i)).createNewFile();
			} catch (Exception ignored) {
			}

		}

		try {
			String dockerHost = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");

			DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
					.withDockerHost(dockerHost)
					.withDockerTlsVerify(false)
					.build();

			new ImageCheck(DockerClientBuilder.getInstance(config).build(), "automatedtestingservice/java-tester").pull();
			new ImageCheck(DockerClientBuilder.getInstance(config).build(), "automatedtestingservice/python-tester").pull();
			new ImageCheck(DockerClientBuilder.getInstance(config).build(), "automatedtestingservice/prolog-tester").pull();
		} catch (Exception ignored) {
		}

		log.info("Done setup");
		priorityQueueService.go();

	}

	private void createDirectory(String home) {
		File file = new File(home);
		if (!file.exists()) {
			if (!file.exists()) {
				file.mkdir();
			}
		}
	}

}
