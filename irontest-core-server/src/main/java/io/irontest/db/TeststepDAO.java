package io.irontest.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.irontest.core.TeststepActionDataBackup;
import io.irontest.models.Endpoint;
import io.irontest.models.MQTeststepProperties;
import io.irontest.models.Teststep;
import io.irontest.models.assertion.Assertion;
import io.irontest.models.assertion.IntegerEqualAssertionProperties;
import io.irontest.models.assertion.XMLEqualAssertionProperties;
import org.skife.jdbi.v2.sqlobject.*;
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static io.irontest.IronTestConstants.DB_UNIQUE_NAME_CONSTRAINT_NAME_SUFFIX;

/**
 * Created by Zheng on 7/07/2015.
 */
@RegisterMapper(TeststepMapper.class)
public abstract class TeststepDAO {
    @SqlUpdate("CREATE SEQUENCE IF NOT EXISTS teststep_sequence START WITH 1 INCREMENT BY 1 NOCACHE")
    public abstract void createSequenceIfNotExists();

    @SqlUpdate("CREATE TABLE IF NOT EXISTS teststep (" +
            "id BIGINT DEFAULT teststep_sequence.NEXTVAL PRIMARY KEY, testcase_id BIGINT NOT NULL, " +
            "sequence SMALLINT NOT NULL, name VARCHAR(200) NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "type VARCHAR(20) NOT NULL, description CLOB, action VARCHAR(50), endpoint_id BIGINT, request BLOB, " +
            "other_properties CLOB NOT NULL, action_data_backup CLOB NOT NULL DEFAULT '{}', " +
            "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "FOREIGN KEY (endpoint_id) REFERENCES endpoint(id), " +
            "FOREIGN KEY (testcase_id) REFERENCES testcase(id) ON DELETE CASCADE, " +
            "CONSTRAINT TESTSTEP_UNIQUE_SEQUENCE_CONSTRAINT UNIQUE(testcase_id, sequence), " +
            "CONSTRAINT TESTSTEP_" + DB_UNIQUE_NAME_CONSTRAINT_NAME_SUFFIX + " UNIQUE(testcase_id, name))")
    public abstract void createTableIfNotExists();

    @CreateSqlObject
    protected abstract EndpointDAO endpointDAO();

    @CreateSqlObject
    protected abstract AssertionDAO assertionDAO();

    @SqlUpdate("insert into teststep (testcase_id, sequence, type, request, endpoint_id, other_properties) " +
            "values (:testcaseId, select coalesce(max(sequence), 0) + 1 from teststep where testcase_id = :testcaseId, " +
            ":type, :request, :endpointId, :otherProperties)")
    @GetGeneratedKeys
    protected abstract long _insert(@Bind("testcaseId") long testcaseId, @Bind("type") String type,
                                    @Bind("request") Object request, @Bind("endpointId") Long endpointId,
                                    @Bind("otherProperties") String otherProperties);

    @SqlUpdate("update teststep set name = :name where id = :id")
    protected abstract long updateNameForInsert(@Bind("id") long id, @Bind("name") String name);

    @Transaction
    public void insert(Teststep teststep) throws JsonProcessingException {
        Endpoint endpoint = teststep.getEndpoint();
        if (endpoint != null) {
            long endpointId = endpointDAO().insertUnmanagedEndpoint(endpoint);
            endpoint.setId(endpointId);
        }
        Object request = teststep.getRequest() instanceof String ?
                ((String) teststep.getRequest()).getBytes() : teststep.getRequest();
        String otherProperties = teststep.getOtherProperties() == null ?
                "{}" : new ObjectMapper().writeValueAsString(teststep.getOtherProperties());
        long id = _insert(teststep.getTestcaseId(), teststep.getType(), request,
                endpoint == null ? null : endpoint.getId(), otherProperties);
        teststep.setId(id);

        String name = "Step " + id;
        updateNameForInsert(id, name);
        teststep.setName(name);
    }

    @SqlUpdate("update teststep set name = :name, description = :description, action = :action, request = :request, " +
            "endpoint_id = :endpointId, other_properties = :otherProperties, " +
            "updated = CURRENT_TIMESTAMP where id = :id")
    protected abstract int _update(@Bind("name") String name, @Bind("description") String description,
                                   @Bind("action") String action, @Bind("request") Object request, @Bind("id") long id,
                                   @Bind("endpointId") Long endpointId,
                                   @Bind("otherProperties") String otherProperties);

    @Transaction
    public Teststep update(Teststep teststep) throws IOException {
        Teststep oldTeststep = findById_NoTransaction(teststep.getId());

        backupRestoreActionData(oldTeststep, teststep);

        Endpoint oldEndpoint = oldTeststep.getEndpoint();
        Endpoint newEndpoint = teststep.getEndpoint();

        Object request = teststep.getRequest() instanceof String ?
                ((String) teststep.getRequest()).getBytes() : teststep.getRequest();
        String otherProperties = teststep.getOtherProperties() == null ?
                "{}" : new ObjectMapper().writeValueAsString(teststep.getOtherProperties());
        _update(teststep.getName(), teststep.getDescription(), teststep.getAction(), request, teststep.getId(),
                newEndpoint == null ? null : newEndpoint.getId(), otherProperties);

        updateEndpointIfExists(oldEndpoint, newEndpoint);

        updateAssertions(teststep);

        return findById_NoTransaction(teststep.getId());
    }

    private void backupRestoreActionData(Teststep oldTeststep, Teststep teststep) throws IOException {
        long teststepId = teststep.getId();
        String oldAction = oldTeststep.getAction();
        String newAction = teststep.getAction();
        if (newAction != null && !newAction.equals(oldAction)) { // we need backup/restore only when switching action
            if (Teststep.TYPE_MQ.equals(teststep.getType())) {
                TeststepActionDataBackup backup = new ObjectMapper().readValue(
                        getActionDataBackupById(teststepId), TeststepActionDataBackup.class);
                boolean backupChanged = false;

                // backup old action's data
                if (Teststep.ACTION_CHECK_DEPTH.equals(oldAction)) {
                    Assertion oldAssertion = oldTeststep.getAssertions().get(0);
                    backup.setQueueDepthAssertionProperties(
                            (IntegerEqualAssertionProperties) oldAssertion.getOtherProperties());
                    backupChanged = true;
                } else if (Teststep.ACTION_DEQUEUE.equals(oldAction)) {
                    Assertion oldAssertion = oldTeststep.getAssertions().get(0);
                    backup.setDequeueAssertionProperties(
                            (XMLEqualAssertionProperties) oldAssertion.getOtherProperties());
                    backupChanged = true;
                } else if (Teststep.ACTION_ENQUEUE.equals(oldAction)) {
                    MQTeststepProperties oldProperties = (MQTeststepProperties) oldTeststep.getOtherProperties();
                    if (MQTeststepProperties.ENQUEUE_MESSAGE_TYPE_TEXT.equals(oldProperties.getEnqueueMessageType())) {
                        backup.setEnqueueMessage(oldTeststep.getRequest());
                        backupChanged = true;
                    } else if (MQTeststepProperties.ENQUEUE_MESSAGE_TYPE_BINARY.equals(
                            oldProperties.getEnqueueMessageType())) {
                        backup.setEnqueueMessage(getBinaryRequestById(teststepId));
                        backupChanged = true;
                    }
                }

                // setup new action's data
                teststep.getAssertions().clear();
                if (Teststep.ACTION_CHECK_DEPTH.equals(newAction)) {
                    Assertion assertion = new Assertion();
                    teststep.getAssertions().add(assertion);
                    assertion.setName("MQ queue depth equals");
                    assertion.setType(Assertion.TYPE_INTEGER_EQUAL);
                    // restore old assertion properties if there is a backup
                    TeststepActionDataBackup oldBackup = new ObjectMapper().readValue(
                            getActionDataBackupById(teststepId), TeststepActionDataBackup.class);
                    IntegerEqualAssertionProperties oldAssertionProperties =
                            oldBackup.getQueueDepthAssertionProperties();
                    if (oldAssertionProperties != null) {
                        assertion.setOtherProperties(oldAssertionProperties);
                    } else {
                        assertion.setOtherProperties(new IntegerEqualAssertionProperties(0));
                    }
                } else if (Teststep.ACTION_DEQUEUE.equals(newAction)) {
                    Assertion assertion = new Assertion();
                    teststep.getAssertions().add(assertion);
                    assertion.setName("Dequeue XML equals");
                    assertion.setType(Assertion.TYPE_XML_EQUAL);
                    // restore old assertion properties if there is a backup
                    TeststepActionDataBackup oldBackup = new ObjectMapper().readValue(
                            getActionDataBackupById(teststepId), TeststepActionDataBackup.class);
                    XMLEqualAssertionProperties oldAssertionProperties = oldBackup.getDequeueAssertionProperties();
                    if (oldAssertionProperties != null) {
                        assertion.setOtherProperties(oldAssertionProperties);
                    } else {
                        assertion.setOtherProperties(new XMLEqualAssertionProperties());
                    }
                } else if (Teststep.ACTION_ENQUEUE.equals(newAction)) {
                    MQTeststepProperties newProperties = (MQTeststepProperties) teststep.getOtherProperties();
                    if (MQTeststepProperties.ENQUEUE_MESSAGE_TYPE_TEXT.equals(newProperties.getEnqueueMessageType())) {
                        // restore old message
                        TeststepActionDataBackup oldBackup = new ObjectMapper().readValue(
                                getActionDataBackupById(teststepId), TeststepActionDataBackup.class);
                        teststep.setRequest(oldBackup.getEnqueueMessage());
                    } else if (MQTeststepProperties.ENQUEUE_MESSAGE_TYPE_BINARY.equals(
                            newProperties.getEnqueueMessageType())) {
                        // restore old message if there is a backup
                        TeststepActionDataBackup oldBackup = new ObjectMapper().readValue(
                                getActionDataBackupById(teststepId), TeststepActionDataBackup.class);
                        saveBinaryRequestById(teststepId, oldBackup.getEnqueueMessage());
                        teststep.setRequest(null);
                    }
                }

                //  persist backup if changed
                if (backupChanged) {
                    String backupStr = new ObjectMapper().writeValueAsString(backup);
                    saveActionDataBackupById(teststepId, backupStr);
                }
            }
        }
    }

    @SqlUpdate("update teststep set action_data_backup = :backupJSON, updated = CURRENT_TIMESTAMP " +
            "where id = :teststepId")
    protected abstract int saveActionDataBackupById(@Bind("teststepId") long teststepId,
                                                    @Bind("backupJSON") String backupJSON);

    @SqlQuery("select action_data_backup from teststep where id = :teststepId")
    protected abstract String getActionDataBackupById(@Bind("teststepId") long teststepId);

    private void updateAssertions(Teststep teststep) throws JsonProcessingException {
        AssertionDAO assertionDAO = assertionDAO();
        List<Long> newAssertionIds = new ArrayList<Long>();
        for (Assertion assertion: teststep.getAssertions()) {
            if (assertion.getId() == null) {    //  insert the assertion
                newAssertionIds.add(assertionDAO.insert(teststep.getId(), assertion));
            } else {
                newAssertionIds.add(assertion.getId());

                //  update the assertion
                assertionDAO.update(assertion);
            }
        }
        //  delete assertions whose id is not in the new assertion id list
        assertionDAO.deleteByTeststepIdIfIdNotIn(teststep.getId(), newAssertionIds);
    }

    private void updateEndpointIfExists(Endpoint oldEndpoint, Endpoint newEndpoint) throws JsonProcessingException {
        if (newEndpoint != null) {
            if (newEndpoint.isManaged()) {
                if (oldEndpoint.isManaged()) {
                    //  do nothing
                } else {
                    if (newEndpoint.getId() == oldEndpoint.getId()) {
                        //  the old unmanaged endpoint is shared by user and becomes managed, so save it
                        endpointDAO().update(newEndpoint);
                    } else {
                        //  the old unmanaged endpoint is replaced by an existing managed endpoint, so delete the old one
                        endpointDAO().deleteById(oldEndpoint.getId());
                    }
                }
            } else {  //  new endpoint is still unmanaged, so update it
                endpointDAO().update(newEndpoint);
            }
        }
    }

    @SqlUpdate("delete from teststep where id = :id")
    protected abstract void _deleteById(@Bind("id") long id);

    @Transaction
    public void deleteById(long id) {
        deleteById_NoTransaction(id);
    }

    public void deleteById_NoTransaction(long id) {
        Teststep teststep = findById_NoTransaction(id);
        _deleteById(id);
        decrementSequenceNumbersOfNextSteps(teststep.getTestcaseId(), (short) (teststep.getSequence() + 1));

        Endpoint endpoint = teststep.getEndpoint();
        if (endpoint != null && !endpoint.isManaged()) {  //  delete the teststep's endpoint if it exists and is unmanaged
            endpointDAO().deleteById(endpoint.getId());
        }
    }

    //  decrement sequence number of all next test steps
    @SqlUpdate("update teststep set sequence = sequence - 1, updated = CURRENT_TIMESTAMP " +
               "where testcase_id = :testcaseId and sequence >= :startSequenceNumber")
    protected abstract int decrementSequenceNumbersOfNextSteps(@Bind("testcaseId") long testcaseId,
                                                               @Bind("startSequenceNumber") short startSequenceNumber);
    @SqlQuery("select * from teststep where id = :id")
    protected abstract Teststep _findById(@Bind("id") long id);

    /**
     * @param id
     * @return the teststep with its associated endpoint
     */
    @Transaction
    public Teststep findById(long id) {
        return findById_NoTransaction(id);
    }

    private void populateTeststepWithMoreInfo(Teststep teststep) {
        Endpoint endpoint = endpointDAO().findById(teststep.getEndpoint().getId());
        teststep.setEndpoint(endpoint);
        teststep.setAssertions(assertionDAO().findByTeststepId(teststep.getId()));
    }

    public Teststep findById_NoTransaction(long id) {
        Teststep teststep = _findById(id);
        populateTeststepWithMoreInfo(teststep);
        return teststep;
    }

    @SqlQuery("select * from teststep where testcase_id = :testcaseId order by sequence")
    protected abstract List<Teststep> _findByTestcaseId(@Bind("testcaseId") long testcaseId);

    @Transaction
    public List<Teststep> findByTestcaseId(@Bind("testcaseId") long testcaseId) {
        List<Teststep> teststeps = _findByTestcaseId(testcaseId);
        for (Teststep teststep: teststeps) {
            populateTeststepWithMoreInfo(teststep);
        }
        return teststeps;
    }

    @SqlQuery("select id, testcase_id, sequence, name, type, description from teststep " +
              "where testcase_id = :testcaseId order by sequence")
    public abstract List<Teststep> findByTestcaseId_PrimaryProperties(@Bind("testcaseId") long testcaseId);

    @SqlQuery("select * from teststep where testcase_id = :testcaseId and sequence = :sequence")
    protected abstract Teststep findBySequence(@Bind("testcaseId") long testcaseId, @Bind("sequence") short sequence);

    @SqlUpdate("update teststep set sequence = :newSequence, updated = CURRENT_TIMESTAMP where id = :teststepId")
    protected abstract int updateSequenceById(@Bind("teststepId") long teststepId, @Bind("newSequence") short newSequence);

    @SqlUpdate("update teststep set sequence = case when :direction = 'up' then sequence - 1 else sequence + 1 end, " +
            "updated = CURRENT_TIMESTAMP " +
            "where testcase_id = :testcaseId and sequence >= :firstSequence and sequence <= :lastSequence")
    protected abstract int batchMoveOneStep(@Bind("testcaseId") long testcaseId,
                                              @Bind("firstSequence") short firstSequence,
                                              @Bind("lastSequence") short lastSequence,
                                              @Bind("direction") String direction);

    @Transaction
    public void moveInTestcase(long testcaseId, short fromSequence, short toSequence) {
        if (fromSequence != toSequence) {
            long draggedStepId = findBySequence(testcaseId, fromSequence).getId();

            //  shelve the dragged step first
            updateSequenceById(draggedStepId, (short) -1);

            if (fromSequence < toSequence) {
                batchMoveOneStep(testcaseId, (short) (fromSequence + 1), toSequence, "up");
            } else {
                batchMoveOneStep(testcaseId, toSequence, (short) (fromSequence - 1), "down");
            }

            //  move the dragged step last
            updateSequenceById(draggedStepId, toSequence);
        }
    }

    @SqlUpdate("update teststep set request = :request, other_properties = :otherProperties, " +
            "updated = CURRENT_TIMESTAMP where id = :teststepId")
    protected abstract int updateRequestAndOtherProperties(@Bind("teststepId") long teststepId,
                                                           @Bind("request") Object request,
                                                           @Bind("otherProperties") String otherProperties);

    @Transaction
    public Teststep setRequestFile(long teststepId, String fileName, InputStream inputStream)
            throws JsonProcessingException {
        Teststep basicTeststep = _findById(teststepId);
        if (Teststep.TYPE_MQ.equals(basicTeststep.getType())) {
            MQTeststepProperties otherProperties = (MQTeststepProperties) basicTeststep.getOtherProperties();
            otherProperties.setEnqueueMessageFilename(fileName);
            String otherPropertiesStr = new ObjectMapper().writeValueAsString(otherProperties);
            updateRequestAndOtherProperties(teststepId, inputStream, otherPropertiesStr);
        }

        return findById_NoTransaction(teststepId);
    }

    @SqlUpdate("update teststep set request = :request, updated = CURRENT_TIMESTAMP where id = :teststepId")
    protected abstract int saveBinaryRequestById(@Bind("teststepId") long teststepId, @Bind("request") Object request);

    @SqlQuery("select request from teststep where id = :teststepId")
    public abstract byte[] getBinaryRequestById(@Bind("teststepId") long teststepId);
}
