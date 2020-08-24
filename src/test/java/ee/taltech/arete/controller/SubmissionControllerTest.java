package ee.taltech.arete.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.taltech.arete.AreteApplication;
import ee.taltech.arete.api.data.request.AreteTestUpdate;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;


@AutoConfigureTestDatabase
@RunWith(SpringRunner.class)
@SpringBootTest(
		classes = AreteApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SubmissionControllerTest {

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@Before
	public void init() {
		RestAssured.defaultParser = Parser.JSON;
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = port;
	}


	@Test
	public void updateImage() {
		given()
				.when()
				.put("/image/prolog-tester")
				.then()
				.statusCode(is(HttpStatus.SC_ACCEPTED));

	}

	@Test
	public void updateTests() {
		AreteTestUpdate update = new AreteTestUpdate(
				new AreteTestUpdate.Project("https://gitlab.cs.ttu.ee/iti0102-2019/ex.git", "iti0102-2019/ex", "iti0102-2019"),
				new ArrayList<>());
		given()
				.body(update)
				.when()
				.put("/tests")
				.then()
				.statusCode(is(HttpStatus.SC_ACCEPTED));

	}
}
