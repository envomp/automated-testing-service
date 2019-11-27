package ee.taltech.arete.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import ee.taltech.arete.domain.InputWriter;
import ee.taltech.arete.domain.Submission;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static com.github.dockerjava.api.model.AccessMode.ro;
import static com.github.dockerjava.api.model.AccessMode.rw;
import static com.github.dockerjava.api.model.HostConfig.newHostConfig;

@Service
public class DockerServiceImpl implements DockerService {

	private static Logger LOGGER = LoggerFactory.getLogger(DockerService.class);

	private ObjectMapper mapper = new ObjectMapper();

	private static void unTar(TarArchiveInputStream tis, File destFile) throws IOException {
		TarArchiveEntry tarEntry = null;
		while ((tarEntry = tis.getNextTarEntry()) != null) {
			if (tarEntry.isDirectory()) {
				if (!destFile.exists()) {
					boolean a = destFile.mkdirs();
				}
			} else {
				FileOutputStream fos = new FileOutputStream(destFile);
				IOUtils.copy(tis, fos);
				fos.close();
			}
		}
		tis.close();
	}

	/**
	 * @param submission : test job to be tested.
	 * @return test job result path
	 */
	public String runDocker(Submission submission, String slug) {

		DockerClient dockerClient = null;
		CreateContainerResponse container = null;
		String imageId;

		String containerName = String.format("%s_%s", submission.getHash().substring(0, 8).toLowerCase(), submission.getThread());
		String hostFile = String.format("input_and_output/%s/host/output.json", submission.getThread());
		TestingPlatforms testingPlatforms = TestingPlatforms.BY_LABEL.get(submission.getTestingPlatform());
		TestingPlatforms.correctTesterInput(submission);
		String image = testingPlatforms.image;

		try {

			String dockerHost = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
			String certPath = System.getenv().getOrDefault("DOCKER_CERT_PATH", "/home/user/.docker/certs");
			String tlsVerify = System.getenv().getOrDefault("DOCKER_TLS_VERIFY", "1");
			String dockerConfig = System.getenv().getOrDefault("DOCKER_CONFIG", "/home/user/.docker");

			DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
					.withDockerHost(dockerHost)
					.withDockerTlsVerify(false)
					.build();

			dockerClient = DockerClientBuilder.getInstance(config).build();

			imageId = getImage(dockerClient, image);

			LOGGER.info("Got image with id: {}", imageId);

			String student = String.format("/students/%s/%s/%s", submission.getUniid(), submission.getProject(), slug);
			String tester = String.format("/tests/%s/%s", submission.getProject(), slug);
			String output = String.format("/input_and_output/%s/host", submission.getThread());

			Volume volumeStudent = new Volume("/student");
			Volume volumeTester = new Volume("/tester");
			Volume volumeOutput = new Volume("/host");

			mapper.writeValue(new File(String.format("input_and_output/%s/host/input.json", submission.getThread())), new InputWriter(String.join(",", submission.getExtra())));

			container = dockerClient.createContainerCmd(imageId)
					.withName(containerName)
					.withVolumes(volumeStudent, volumeTester, volumeOutput)
					.withAttachStdout(true)
					.withAttachStderr(true)
					.withHostConfig(newHostConfig()
							.withBinds(
									new Bind(new File(output).getAbsolutePath(), volumeOutput, rw),
									new Bind(new File(student).getAbsolutePath(), volumeStudent, rw),
									new Bind(new File(tester).getAbsolutePath(), volumeTester, ro)))
					.exec();

			LOGGER.info("Created container with id: {}", container.getId());

			dockerClient.startContainerCmd(container.getId()).exec();
			LOGGER.info("Started container with id: {}", container.getId());

			Integer statusCode = dockerClient.waitContainerCmd(container.getId())
					.exec(new WaitContainerResultCallback())
					.awaitStatusCode();
			LOGGER.info("Docker finished with status code: {}", statusCode);

		} catch (Exception e) {

			e.printStackTrace();
			LOGGER.error("Job failed with exception: {}", e.getMessage());
		}

		if (dockerClient != null && container != null) {

			LOGGER.info("Stopping container: {}", container.getId());
			try {
				dockerClient.stopContainerCmd(container.getId()).withTimeout(200).exec();
			} catch (Exception stop) {
				LOGGER.info("Container {} has already been stopped", container.getId());
			}

			LOGGER.info("Removing container: {}", container.getId());
			try {
				dockerClient.removeContainerCmd(container.getId()).exec();
			} catch (Exception remove) {
				LOGGER.error("Container {} has already been removed", submission.getHash());
			}
		}

		return hostFile;
	}

	private String getImage(DockerClient dockerClient, String image) throws InterruptedException {

		ImageCheck imageCheck = new ImageCheck(dockerClient, image);
		imageCheck.invoke();
		return imageCheck.getTester().getId();

	}

}
