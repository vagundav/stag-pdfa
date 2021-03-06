package org.resources;


import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;

import org.api.*;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Path("/api")
public final class ApiResource {
    private static ArrayList<String> ruleViolationExceptions = new ArrayList<String>();
    private static String urlToVeraPDFrest;
    private static String pathToSentFilesFolder;
    private static SQLite databaseInstance;
    private static LinkedHashMap<String, List<String>> stagpdfa;
    private static String delayProcessingTheRequest;
    private static String testSwitch;
    private static String inputStreamProcessor;
    private static ArrayList<String> emailProperties;

    public ApiResource(Map stagpdfa) {
        //source: https://stackoverflow.com/questions/49771099/how-to-get-string-from-config-yml-file-in-dropwizard-resource
        //source: https://stackoverflow.com/questions/13581997/how-get-value-from-linkedhashmap-based-on-index-not-on-key?answertab=votes#tab-top

        this.stagpdfa = new LinkedHashMap<String, List<String>>(stagpdfa);
        ruleViolationExceptions = new ArrayList<String>(this.stagpdfa.get("exceptions"));
        this.pathToSentFilesFolder = this.stagpdfa.get("pathToSentFilesFolder").get(0);
        this.urlToVeraPDFrest = this.stagpdfa.get("urlToVeraPDFrest").get(0);
        this.databaseInstance = new SQLite(this.stagpdfa.get("databaseUrlJdbc").get(0), this.stagpdfa.get("cleanDatabaseTableAtStart").get(0));
        this.delayProcessingTheRequest = this.stagpdfa.get("delayProcessingTheRequest").get(0);
        this.testSwitch = this.stagpdfa.get("testSwitch").get(0);
        this.inputStreamProcessor = this.stagpdfa.get("inputStramProcessor").get(0);
        this.emailProperties = new ArrayList<String>(this.stagpdfa.get("javaMail"));
    }

    @GET
    @Path("/ok")
    @Produces(MediaType.APPLICATION_JSON)//APPLICATION_JSON
    public Response getOkResponse() {
        //source: https://www.baeldung.com/jax-rs-response
        String message = "{\"hello\": \"This is a JSON response\"}";

        return Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(message)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @POST
    @Path("/validate/auto")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces({MediaType.APPLICATION_JSON})
    public static Response savePdf(@FormDataParam("file") InputStream uploadedInputStream) {

        //time of processing on stag-pdfa
        StopWatch request_time = StopWatch.createStarted();

        //for purpose of testing,
        if (delayProcessingTheRequest.equals("true")) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException inter) {
                inter.getStackTrace();
            }
        }

        String nameForPdf = "";
        Timestamp datetime = new Timestamp(System.currentTimeMillis());//System.currentTimeMillis()
        InputStream inputStreamFromClass;
        Email email = new Email(emailProperties);
        CustomHttpClient client = new CustomHttpClient(urlToVeraPDFrest);
        try {
            if (inputStreamProcessor.equals("InputStreamProcessor2")) {
                InputStreamProcessor isp = new InputStreamProcessor2(pathToSentFilesFolder);
                nameForPdf = isp.saveFileAndCalculateSHA1(uploadedInputStream);
                //load input stream from bytesArray
                inputStreamFromClass = isp.createInputStream();
            } else if (inputStreamProcessor.equals("InputStreamProcessor1")) {
                InputStreamProcessor isp = new InputStreamProcessor1(pathToSentFilesFolder);
                nameForPdf = isp.saveFileAndCalculateSHA1(uploadedInputStream);
                //load input stream from file
                inputStreamFromClass = isp.createInputStream();
            } else {
                //switch off saveing processed file on disk and calculating sha1
                inputStreamFromClass = uploadedInputStream;
            }

            //create log to logging table
            databaseInstance.insertStagpdfaLogs(nameForPdf, datetime);


            client.createRequest(testSwitch, inputStreamFromClass);
            client.sendRequest();
            client.processResponse(ruleViolationExceptions);

        } catch (UnrecognizedPropertyException ext) {
            client.setErrorMessage(ExceptionUtils.getStackTrace(ext));
        } catch (IOException ext) {
            client.setErrorMessage(ExceptionUtils.getStackTrace(ext));
        } catch (NoSuchAlgorithmException ext) {
            client.setErrorMessage(ExceptionUtils.getStackTrace(ext));
        } finally {
            System.out.println(client.getErrorMessage());
        }
        request_time.stop();

        //source: https://docs.oracle.com/cd/E19830-01/819-4721/beajw/index.html
        //update last inserted record
        databaseInstance.updateStagpdfaLogs(client.getVera_pdf_rest_response(), (int) request_time.getTime(TimeUnit.MILLISECONDS), (int) client.getVerapdf_rest_request_time().getTime(TimeUnit.MILLISECONDS), client.getStatusCode(), client.getErrorMessage(), datetime);

        Response response;
        if (client.getErrorMessage().isEmpty()) {
            response = Response
                    .status(Response.Status.OK)
                    .entity(client.getResponseMessage())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } else {
            email.sendEmail(client.getErrorMessage());
            response = Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"Error 500 Internal Server Error\"} ")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        //only for testing purpouses
        System.out.println("response Message returned to IS stag: ");
        System.out.println(client.getResponseMessage());
        response = setTestFaultyResponseMessage(email, response);

        return response;
    }

    //Set of tests for finding out, how IS/STAGT cope with unexpect responeses of stag-pdfa
    public static Response setTestFaultyResponseMessage(Email email, Response response) {
        String testResponseMessage = "";
        if (testSwitch.equals("f5") || testSwitch.equals("f32") || testSwitch.equals("f4")) {
            if (testSwitch.equals("f5")) {//TEST f5 - Shape of response is JSON: Key is as expected, value is unexpected.
                testResponseMessage = "{\"compliant\": \"Response from veraPDF wasn't in Content-type: application/json \"}";
                email.sendEmail(testResponseMessage);
            } else if (testSwitch.equals("f32")) {//TEST f32 - Shape of response is JSON: Key is missing, value is unexpected.
                testResponseMessage = "{\"Response from veraPDF wasn't in Content-type: application/json \"}";
                email.sendEmail(testResponseMessage);
            } else if (testSwitch.equals("f4")) {//TEST f4 - Shape of response is JSON: Key is unexpected, value is unexpected.
                testResponseMessage = "{\"klic\": \"Response from veraPDF wasn't in Content-type: application/json \"}";
                email.sendEmail(testResponseMessage);
            }
            response = Response
                    .status(Response.Status.OK)
                    .entity(testResponseMessage)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } else if (testSwitch.equals("f31")) {//Shape of response is HTML
            System.out.println("response Message returned to IS stag: ");
            testResponseMessage = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\" /></head><body><h2>This is test response in html</h2></body></html>";
            System.out.println(testResponseMessage);
            email.sendEmail(testResponseMessage);
            response = Response
                    .status(Response.Status.OK)
                    .type(MediaType.TEXT_HTML)
                    .entity(testResponseMessage)
                    .build();
        }
        return response;
    }
}
