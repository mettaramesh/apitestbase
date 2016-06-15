package io.irontest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.irontest.db.TeststepDAO;
import io.irontest.models.Endpoint;
import io.irontest.models.Teststep;
import io.irontest.models.WaitTeststepProperties;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * Created by Zheng on 11/07/2015.
 */
@Path("/testcases/{testcaseId}/teststeps") @Produces({ MediaType.APPLICATION_JSON })
public class TeststepResource {
    private final TeststepDAO teststepDAO;

    public TeststepResource(TeststepDAO teststepDAO) {
        this.teststepDAO = teststepDAO;
    }

    @POST
    public Teststep create(Teststep teststep) throws JsonProcessingException {
        preCreationProcess(teststep);

        teststepDAO.insert(teststep);

        return teststep;
    }

    //  adding more info to the teststep object
    private void preCreationProcess(Teststep teststep) {
        //  create sample request
        String sampleRequest = null;
        if (Teststep.TEST_STEP_TYPE_DB.equals(teststep.getType())){
            sampleRequest = "select * from ? where ?";
        }
        teststep.setRequest(sampleRequest);

        //  create unmanaged endpoint
        if (!Teststep.TEST_STEP_TYPE_WAIT.equals(teststep.getType())) {
            Endpoint endpoint = new Endpoint();
            endpoint.setName("Unmanaged Endpoint");
            if (Teststep.TEST_STEP_TYPE_SOAP.equals(teststep.getType())) {
                endpoint.setType(Endpoint.ENDPOINT_TYPE_SOAP);
            } else if (Teststep.TEST_STEP_TYPE_DB.equals(teststep.getType())) {
                endpoint.setType(Endpoint.ENDPOINT_TYPE_DB);
            } else if (Teststep.TEST_STEP_TYPE_IIB.equals(teststep.getType())) {
                endpoint.setType(Endpoint.ENDPOINT_TYPE_MQIIB);
            } else if (Teststep.TEST_STEP_TYPE_MQ.equals(teststep.getType())) {
                endpoint.setType(Endpoint.ENDPOINT_TYPE_MQIIB);
            }
            teststep.setEndpoint(endpoint);
        }

        //  set initial seconds for Wait test step
        if (Teststep.TEST_STEP_TYPE_WAIT.equals(teststep.getType())) {
            teststep.setOtherProperties(new WaitTeststepProperties(1));   //  there is no point to wait for 0 seconds
        }
    }

    @GET
    @Path("{teststepId}")
    public Teststep findById(@PathParam("teststepId") long teststepId) {
        return teststepDAO.findById(teststepId);
    }

    @PUT @Path("{teststepId}")
    public Teststep update(Teststep teststep) throws JsonProcessingException, InterruptedException {
        Thread.sleep(100);  //  workaround for Chrome 44 to 48's 'Failed to load response data' problem (no such problem in Chrome 49)
        return teststepDAO.update(teststep);
    }

    @DELETE @Path("{teststepId}")
    public void delete(@PathParam("teststepId") long teststepId) {
        teststepDAO.deleteById(teststepId);
    }
}