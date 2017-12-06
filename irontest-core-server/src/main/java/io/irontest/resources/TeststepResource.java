package io.irontest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.irontest.core.runner.*;
import io.irontest.db.TeststepDAO;
import io.irontest.db.UserDefinedPropertyDAO;
import io.irontest.db.UtilsDAO;
import io.irontest.models.AppInfo;
import io.irontest.models.AppMode;
import io.irontest.models.UserDefinedProperty;
import io.irontest.models.endpoint.Endpoint;
import io.irontest.models.endpoint.MQConnectionMode;
import io.irontest.models.endpoint.MQEndpointProperties;
import io.irontest.models.endpoint.SOAPEndpointProperties;
import io.irontest.models.teststep.*;
import io.irontest.utils.IronTestUtils;
import io.irontest.utils.XMLUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.annotation.security.PermitAll;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.irontest.IronTestConstants.IMPLICIT_PROPERTY_DATE_TIME_FORMAT;
import static io.irontest.IronTestConstants.IMPLICIT_PROPERTY_NAME_TEST_STEP_START_TIME;

/**
 * Created by Zheng on 11/07/2015.
 */
@Path("/testcases/{testcaseId}/teststeps") @Produces({ MediaType.APPLICATION_JSON })
public class TeststepResource {
    private final AppInfo appInfo;
    private final TeststepDAO teststepDAO;
    private final UserDefinedPropertyDAO udpDAO;
    private final UtilsDAO utilsDAO;

    public TeststepResource(AppInfo appInfo, TeststepDAO teststepDAO, UserDefinedPropertyDAO udpDAO, UtilsDAO utilsDAO) {
        this.appInfo = appInfo;
        this.teststepDAO = teststepDAO;
        this.udpDAO = udpDAO;
        this.utilsDAO = utilsDAO;
    }

    @POST
    @PermitAll
    public Teststep create(Teststep teststep) throws JsonProcessingException {
        preCreationProcess(teststep);

        return teststepDAO.insert(teststep);
    }

    //  adding more info to the teststep object
    private void preCreationProcess(Teststep teststep) {
        //  create sample request
        String sampleRequest = null;
        if (Teststep.TYPE_DB.equals(teststep.getType())){
            sampleRequest = "select * from ? where ?";
        }
        teststep.setRequest(sampleRequest);

        //  create unmanaged endpoint
        if (!Teststep.TYPE_WAIT.equals(teststep.getType())) {
            Endpoint endpoint = new Endpoint();
            endpoint.setName("Unmanaged Endpoint");
            if (Teststep.TYPE_SOAP.equals(teststep.getType())) {
                endpoint.setType(Endpoint.TYPE_SOAP);
                endpoint.setOtherProperties(new SOAPEndpointProperties());
            } else if (Teststep.TYPE_DB.equals(teststep.getType())) {
                endpoint.setType(Endpoint.TYPE_DB);
            } else if (Teststep.TYPE_MQ.equals(teststep.getType())) {
                endpoint.setType(Endpoint.TYPE_MQ);
                MQEndpointProperties endpointProperties = new MQEndpointProperties();
                endpointProperties.setConnectionMode(
                        appInfo.getAppMode() == AppMode.LOCAL ? MQConnectionMode.BINDINGS : MQConnectionMode.CLIENT);
                endpoint.setOtherProperties(endpointProperties);
            } else if (Teststep.TYPE_IIB.equals(teststep.getType())) {
                endpoint.setType(Endpoint.TYPE_IIB);
            }
            teststep.setEndpoint(endpoint);
        }

        //  set initial/default property values (in the Properties sub-class)
        if (Teststep.TYPE_SOAP.equals(teststep.getType())) {
            teststep.setOtherProperties(new SOAPTeststepProperties());
        } else if (Teststep.TYPE_MQ.equals(teststep.getType())) {
            teststep.setOtherProperties(new MQTeststepProperties());
        } else if (Teststep.TYPE_WAIT.equals(teststep.getType())) {
            teststep.setOtherProperties(new WaitTeststepProperties(1000));   //  there is no point to wait for 0 milliseconds
        }
    }

    private void populateParametersInWrapper(TeststepWrapper wrapper) throws Exception {
        Teststep teststep = wrapper.getTeststep();
        if (Teststep.TYPE_DB.equals(teststep.getType())) {
            boolean isSQLRequestSingleSelectStatement;
            try {
                isSQLRequestSingleSelectStatement = IronTestUtils.isSQLRequestSingleSelectStatement(
                        (String) teststep.getRequest());
            } catch (Exception e) {
                //  the SQL script is invalid, so it can't be a single select statement
                //  swallow the exception to avoid premature error message on UI (user is still editing the SQL script)
                //  the exception will popup on UI when user executes the script (the script will be reparsed at that time)
                isSQLRequestSingleSelectStatement = false;
            }
            wrapper.getParameters().put("isSQLRequestSingleSelectStatement", isSQLRequestSingleSelectStatement);
        }
    }

    @GET @Path("{teststepId}")
    public TeststepWrapper findById(@PathParam("teststepId") long teststepId) throws Exception {
        TeststepWrapper wrapper = new TeststepWrapper();
        Teststep teststep = teststepDAO.findById(teststepId);
        wrapper.setTeststep(teststep);
        populateParametersInWrapper(wrapper);

        return wrapper;
    }

    @PUT @Path("{teststepId}")
    @PermitAll
    public TeststepWrapper update(Teststep teststep) throws Exception {
        Thread.sleep(100);  //  workaround for Chrome's 'Failed to load response data' problem (still exist in Chrome 61)

        TeststepWrapper wrapper = new TeststepWrapper();
        teststep = teststepDAO.update(teststep);
        wrapper.setTeststep(teststep);
        populateParametersInWrapper(wrapper);

        return wrapper;
    }

    @DELETE @Path("{teststepId}")
    @PermitAll
    public void delete(@PathParam("teststepId") long teststepId) {
        teststepDAO.deleteById(teststepId);
    }

    /**
     * Run a test step individually (not as part of test case running).
     * @param teststep
     * @return API response
     */
    @POST @Path("{teststepId}/run")
    @PermitAll
    public BasicTeststepRun run(Teststep teststep) throws Exception {
        //  get UDPs defined on the test case
        List<UserDefinedProperty> testcaseUDPs = udpDAO.findByTestcaseId(teststep.getTestcaseId());
        //  get implicit properties
        Map<String, String> implicitProperties = new HashMap<>();
        implicitProperties.put(IMPLICIT_PROPERTY_NAME_TEST_STEP_START_TIME,
                new SimpleDateFormat(IMPLICIT_PROPERTY_DATE_TIME_FORMAT).format(new Date()));

        //  run the test step
        TeststepRunner teststepRunner = TeststepRunnerFactory.getInstance().newTeststepRunner(
                teststep, teststepDAO, utilsDAO, implicitProperties, testcaseUDPs, null);
        BasicTeststepRun basicTeststepRun = teststepRunner.run();

        //  for better display in browser, transform XML response to be pretty-printed
        if (Teststep.TYPE_SOAP.equals(teststep.getType())) {
            SOAPAPIResponse soapAPIResponse = (SOAPAPIResponse) basicTeststepRun.getResponse();
            soapAPIResponse.setHttpBody(XMLUtils.prettyPrintXML(soapAPIResponse.getHttpBody()));
        } else if (Teststep.TYPE_MQ.equals(teststep.getType()) &&
                Teststep.ACTION_DEQUEUE.equals(teststep.getAction())) {
            MQAPIResponse mqAPIResponse = (MQAPIResponse) basicTeststepRun.getResponse();
            mqAPIResponse.setValue(XMLUtils.prettyPrintXML((String) mqAPIResponse.getValue()));
        }

        return basicTeststepRun;
    }

    /**
     * Save the uploaded file as Teststep.request.
     * Use @POST instead of @PUT because ng-file-upload seems not working with PUT.
     * @param teststepId
     * @param inputStream
     * @param contentDispositionHeader
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @POST @Path("{teststepId}/requestFile")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @PermitAll
    public Teststep saveRequestFile(@PathParam("teststepId") long teststepId,
                                      @FormDataParam("file") InputStream inputStream,
                                      @FormDataParam("file") FormDataContentDisposition contentDispositionHeader)
            throws IOException, InterruptedException {
        return teststepDAO.setRequestFile(teststepId, contentDispositionHeader.getFileName(), inputStream);
    }

    /**
     * Download Teststep.request as a file.
     * @param teststepId
     * @return
     * @throws IOException
     */
    @GET @Path("{teststepId}/requestFile")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getRequestFile(@PathParam("teststepId") long teststepId) throws IOException {
        Teststep teststep = teststepDAO.findById(teststepId);
        teststep.setRequest(teststepDAO.getBinaryRequestById(teststep.getId()));
        String filename = teststep.getRequestFilename() == null ? "UnknownFilename" : teststep.getRequestFilename();
        return Response.ok(teststep.getRequest())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }
}
