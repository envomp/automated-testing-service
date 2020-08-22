package ee.taltech.arete.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import ee.taltech.arete.api.data.response.arete.File;
import ee.taltech.arete.domain.InputWriter;
import ee.taltech.arete.domain.Submission;
import ee.taltech.arete.exception.ImageNotFoundException;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.github.dockerjava.api.model.AccessMode.ro;
import static com.github.dockerjava.api.model.AccessMode.rw;
import static com.github.dockerjava.api.model.HostConfig.newHostConfig;

public class Docker {

	private static final String home = System.getenv().getOrDefault("ARETE_HOME", System.getenv("HOME") + "/arete");
	private static final Logger LOGGER = LoggerFactory.getLogger(Docker.class);
	public String outputPath;
	private final ObjectMapper mapper = new ObjectMapper();

	private DockerClient dockerClient;
	private CreateContainerResponse container;
	private final String containerName;
	private final String image;

	private final Submission submission;
	private final String slug;

	private boolean done = false;

	public Docker(Submission submission, String slug) {
		this.submission = submission;
		this.slug = slug;
		this.containerName = String.format("%s_%s", submission.getHash().substring(0, 16).toLowerCase(), 100000 + Math.abs(new Random().nextInt()) * 900000);
		this.outputPath = String.format("input_and_output/%s/host", submission.getHash());
		this.image = String.format("automatedtestingservice/%s-tester", submission.getTestingPlatform());
	}

	public void run() {
		try {

			String dockerHost = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
//			String certPath = System.getenv().getOrDefault("DOCKER_CERT_PATH", "/home/user/.docker/certs");
//			String tlsVerify = System.getenv().getOrDefault("DOCKER_TLS_VERIFY", "1");
//			String dockerConfig = System.getenv().getOrDefault("DOCKER_CONFIG", "/home/user/.docker");

			DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
					.withDockerHost(dockerHost)
					.withDockerTlsVerify(false)
					.build();

			dockerClient = DockerClientBuilder.getInstance(config).build();

			String imageId = getImage(dockerClient, image);

			LOGGER.info("Got image with id: {}", imageId);

			///  PROCEED TO MODIFY WITH CAUTION  ///

			String output = String.format("input_and_output/%s/host", submission.getHash());
			String testerHost = String.format("input_and_output/%s/tester", submission.getHash());
			String studentHost = String.format("input_and_output/%s/student", submission.getHash());

			String tester = String.format("tests/%s/%s", submission.getCourse(), slug);
			String tempTester = String.format("input_and_output/%s/tester", submission.getHash());

			String student;

			student = String.format("students/%s/%s/%s", submission.getUniid(), submission.getFolder(), slug);
			String tempStudent = String.format("input_and_output/%s/student", submission.getHash());

			Volume volumeStudent = new Volume("/student");
			Volume volumeTester = new Volume("/tester");
			Volume volumeOutput = new Volume("/host");

			try {
				// copy student files to tester
				if (submission.getSource() == null) {
					FileUtils.copyDirectory(new java.io.File(student), new java.io.File(tempStudent));
				} else {
					for (File file : submission.getSource()) {
						copyFilesFromSource(tempStudent, file);
					}
				}
			} catch (IOException e) {
				LOGGER.error("Failed to copy files from student folder to temp folder.");
				throw new IOException(e.getMessage());
			}

			try {

				// copy tester files to tester
				if (submission.getTestSource() == null) {
					FileUtils.copyDirectory(new java.io.File(tester), new java.io.File(tempTester));
				} else {
					for (File file : submission.getTestSource()) {
						copyFilesFromSource(tempTester, file);
					}
				}

			} catch (IOException e) {
				LOGGER.error("Failed to copy files from tester folder to temp folder.");
				throw new IOException(e.getMessage());
			}

			mapper.writeValue(new java.io.File(String.format("input_and_output/%s/host/input.json", submission.getHash())), new InputWriter(String.join(",", submission.getDockerExtra())));

			container = dockerClient.createContainerCmd(imageId)
					.withName(containerName)
					.withVolumes(volumeStudent, volumeTester, volumeOutput)
					.withAttachStdout(true)
					.withAttachStderr(true)
					.withHostConfig(newHostConfig()
							.withBinds(
									new Bind(new java.io.File(output).getAbsolutePath(), volumeOutput, rw),
									new Bind(new java.io.File(studentHost).getAbsolutePath(), volumeStudent, rw),
									new Bind(new java.io.File(testerHost).getAbsolutePath(), volumeTester, ro))
							.withCpuCount((submission.getPriority() > 7 ? 4L : 2L))
					).exec();

			///   END OF WARNING   ///

			LOGGER.info("Created container with id: {}", container.getId());

			dockerClient.startContainerCmd(container.getId()).exec();
			LOGGER.info("Started container with id: {}", container.getId());

//			dockerClient.waitContainerCmd(container.getId())
//					.exec(new WaitContainerResultCallback());

			StringBuilder builder = new StringBuilder(); //intermediate variable to get std

			dockerClient
					.logContainerCmd(containerName)
					.withStdErr(true)
					.withStdOut(true)
					.withFollowStream(true)
					.withSince(0)
					.exec(new ResultCallbackTemplate<LogContainerResultCallback, Frame>() {
						@Override
						public void onNext(Frame frame) {
							if (!submission.getSystemExtra().contains("noStd")) {
								builder.append(new String(frame.getPayload()));
							}
						}

						@Override
						public void onComplete() {
							submission.setResult(builder.toString());
							done = true;
							LOGGER.info("Docker for user {} with slug {} finished", submission.getUniid(), slug);
							super.onComplete();
						}
					});


			int seconds = submission.getDockerTimeout();
			while (!done) {
				TimeUnit.SECONDS.sleep(1);
				seconds--;
				if (seconds == 0) {
					throw new TimeoutException("Timed out");
				}
			}

		} catch (Exception e) {
			LOGGER.error("Exception caught while running docker: {}", e.getMessage());
			throw new DockerException("Exception in docker, message: " + e.getMessage(), 1);
		}
	}

	private void copyFilesFromSource(String tempStudent, File file) throws IOException {
		String temp;
		try {
			temp = file.getPath().substring(file.getPath().indexOf("\\"));
		} catch (Exception e) {
			temp = file.getPath().substring(file.getPath().indexOf("/"));
		}

		java.io.File path = new java.io.File(String.format("%s/%s", tempStudent, temp));
		path.getParentFile().mkdirs();
		FileWriter writer = new FileWriter(path);
		writer.write(file.getContents());
		writer.close();
	}

	public void cleanup() {
		if (dockerClient != null && container != null) {

			try {
				dockerClient.stopContainerCmd(container.getId()).exec();
				LOGGER.info("Stopped container: {}", container.getId());
			} catch (Exception stop) {
				LOGGER.info("Container {} has already been stopped", container.getId());
			}

			try {
				dockerClient.removeContainerCmd(container.getId()).exec();
				LOGGER.info("Removed container: {}", container.getId());
			} catch (Exception remove) {
				LOGGER.error("Container {} has already been removed", submission.getHash());
			}
		}
	}

	private String getImage(DockerClient dockerClient, String image) {

		try {
			ImageCheck imageCheck = new ImageCheck(dockerClient, image);
			imageCheck.invoke();
			return imageCheck.getTester().getId();
		} catch (Exception e) {
			throw new ImageNotFoundException(e.getMessage());
		}
	}

}
