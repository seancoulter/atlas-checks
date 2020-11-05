package org.openstreetmap.atlas.checks.maproulette.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.StringUtils;
import org.openstreetmap.atlas.checks.flag.CheckFlag;
import org.openstreetmap.atlas.checks.flag.serializer.CheckFlagDeserializer;
import org.openstreetmap.atlas.exception.CoreException;
import org.openstreetmap.atlas.geography.Location;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.openstreetmap.atlas.geography.atlas.change.FeatureChange;
import org.openstreetmap.atlas.geography.atlas.change.description.ChangeDescription;
import org.openstreetmap.atlas.geography.atlas.change.description.ChangeDescriptorType;
import org.openstreetmap.atlas.geography.atlas.change.description.descriptors.ChangeDescriptor;
import org.openstreetmap.atlas.geography.atlas.change.description.descriptors.TagChangeDescriptor;

/**
 * A task is a single unit of work in the MapRoulette Challenge
 *
 * @author cuthbertm
 * @author mgostintsev
 */
public class Task
{
    /**
     * A class holding a point and some description for that point
     */
    private class PointInformation
    {
        private final Location location;
        private final Optional<String> description;

        PointInformation(final Location location, final Optional<String> description)
        {
            this.location = location;
            this.description = description;
        }

        public Optional<String> getDescription()
        {
            return this.description;
        }

        public Location getLocation()
        {
            return this.location;
        }
    }

    /**
     *
     */
    public static class FixSuggestionToCooperativeWorkConvertor
    {
        private final Set<FeatureChange> featureChanges;

        public FixSuggestionToCooperativeWorkConvertor(final Set<FeatureChange> featureChanges)
        {
            this.featureChanges = featureChanges;
        }

        final List<JsonObject> convert()
        {
            final List<JsonObject> operationsList = new ArrayList<>();
            for (final FeatureChange featureChange : this.featureChanges)
            {
                final ChangeDescription whatChanged = featureChange.explain();
                operationsList.add(new TagChangeOperation(whatChanged).create().json);
            }
            // convert each featureChange to a cooperative work object
            return operationsList;
        }
    }

    public static class TagChangeOperation
    {
        private final ChangeDescriptorType operationType;
        private final long id;
        private final List<ChangeDescriptor> operations;
        private JsonObject json;
        private final String OPERATION_TYPE = "operationType";
        private final String DATA = "data";
        private final String ID = "id";
        private final String OPERATIONS = "operations";
        private final String OPERATION = "operation";

        public TagChangeOperation(final ChangeDescription changeDescription)
        {
            this.operationType = changeDescription.getChangeDescriptorType();
            this.id = changeDescription.getIdentifier();
            this.operations = changeDescription.getChangeDescriptors();
        }

        /**
         *
         * @return
         */
        public TagChangeOperation create()
        {
            this.json = new JsonObject();
            final JsonObject data = new JsonObject();
            data.add(this.ID, new JsonPrimitive(this.id));
            final JsonArray operationsArray = new JsonArray();
            this.operations.forEach(operation ->
            {
                final JsonObject nestedOperation = new JsonObject();
                final String action = this.convertChangeDescriptorType(operation.getChangeDescriptorType());
                nestedOperation.add(this.OPERATION, new JsonPrimitive(action));
                if ("unsetTags".equals(action))
                {
                    final JsonArray tagChanges = new JsonArray();
                    tagChanges.add(new JsonPrimitive(((TagChangeDescriptor) operation).getKey()));
                    nestedOperation.add(this.DATA, tagChanges);
                }
                else if ("setTags".equals(action))
                {
                    final JsonObject tagChanges = new JsonObject();
                    tagChanges.add(((TagChangeDescriptor) operation).getKey(), new JsonPrimitive(((TagChangeDescriptor) operation).getValue()));
                    nestedOperation.add(this.DATA, tagChanges);
                }
                operationsArray.add(nestedOperation);
            });
            data.add(this.OPERATIONS, operationsArray);
            this.json.add(OPERATION_TYPE, new JsonPrimitive(this.convertAction(this.operationType)));
            this.json.add(this.DATA, data);
            return this;
        }

        /**
         *
         * @param descriptorType
         * @return
         */
        private String convertAction(final ChangeDescriptorType descriptorType)
        {
            if ("UPDATE".equals(descriptorType.name()))
            {
                return "modifyElement";
            }
            if ("REMOVE".equals(descriptorType.name()))
            {
                return "removeElement";
            }
            // Unsupported
            return "";
        }

        /**
         *
         * @param descriptorType
         * @return
         */
        private String convertChangeDescriptorType(final ChangeDescriptorType descriptorType)
        {
            if ("UPDATE".equals(descriptorType.name()))
            {
                return "setTags";
            }
            if ("REMOVE".equals(descriptorType.name()))
            {
                return "unsetTags";
            }
            // Unsupported
            return "";
        }
    }

    protected static final String FEATURE = "Feature";
    protected static final String POINT = "Point";
    protected static final String TASK_FEATURES = "features";
    protected static final String TASK_FEATURE_COORDINATES = "coordinates";
    protected static final String TASK_FEATURE_GEOMETRY = "geometry";
    protected static final String TASK_FEATURE_PROPERTIES = "properties";
    protected static final String TASK_GEOMETRIES = "geometries";
    protected static final String TASK_INSTRUCTION = "instruction";
    protected static final String TASK_NAME = "name";
    protected static final String TASK_PARENT_ID = "parent";
    protected static final String TASK_TYPE = "type";
    private static final String KEY_DESCRIPTION = "description";
    private static final String CHECK_GENERATOR = "flag:check";
    private static final String FRAMEWORK_GENERATOR = "flag:generator";
    private static final String FRAMEWORK = "Atlas Checks";
    private String challengeName;
    private Optional<JsonArray> geoJson = Optional.empty();
    private String instruction;
    private final Set<PointInformation> points = new HashSet<>();
    private String projectName = "";
    private String taskIdentifier;
    private List<JsonObject> cooperativeWork;

    public Task()
    {

    }

    public void addPoint(final Location point, final String description)
    {
        this.points.add(new PointInformation(point, Optional.ofNullable(description)));
    }

    /**
     * What defines a task as unique is its task identifier and its challenge name. So even if the
     * geometry or description or other member variables are different, it will be defined as equal
     * if those two values are equal
     *
     * @param obj
     *            The object to compare it against
     * @return whether it matches the supplied object
     */
    @Override
    public boolean equals(final Object obj)
    {
        return obj instanceof Task
                && StringUtils.equals(this.taskIdentifier, ((Task) obj).getTaskIdentifier())
                && StringUtils.equals(this.challengeName, ((Task) obj).getChallengeName());
    }

    public JsonObject generateTask(final long parentIdentifier)
    {
        final JsonObject task = new JsonObject();
        final JsonObject result = new JsonObject();
        result.addProperty(TASK_TYPE, "FeatureCollection");
        result.add(TASK_FEATURES, this.generateTaskFeatures(this.points, this.geoJson));
        task.add(TASK_INSTRUCTION, new JsonPrimitive(this.instruction));
        task.add(TASK_NAME, new JsonPrimitive(this.getTaskIdentifier()));
        task.add(TASK_PARENT_ID, new JsonPrimitive(parentIdentifier));
        task.add(TASK_GEOMETRIES, result);
        return task;
    }

    public String getChallengeName()
    {
        return this.challengeName;
    }

    public Optional<JsonArray> getGeoJson()
    {
        return this.geoJson;
    }

    public String getInstruction()
    {
        return this.instruction;
    }

    public Set<Location> getPoints()
    {
        return this.points.parallelStream().map(PointInformation::getLocation)
                .collect(Collectors.toSet());
    }

    public String getProjectName()
    {
        return this.projectName;
    }

    public String getTaskIdentifier()
    {
        return this.taskIdentifier;
    }

    @Override
    public int hashCode()
    {
        return this.taskIdentifier.hashCode() + this.challengeName.hashCode();
    }

    public void setChallengeName(final String challengeName)
    {
        this.challengeName = challengeName;
    }

    public void setCooperativeWork(final Set<FeatureChange> fixSuggestions)
    {
        final List<JsonObject> cooperativeWork = new FixSuggestionToCooperativeWorkConvertor(fixSuggestions).convert();
        this.cooperativeWork = cooperativeWork;
    }

    public void setGeoJson(final Optional<JsonArray> geoJson)
    {
        this.geoJson = geoJson;
    }

    public void setInstruction(final String instruction)
    {
        this.instruction = instruction;
    }

    public void setPoint(final Location point)
    {
        this.points.add(new PointInformation(point, Optional.empty()));
    }

    public void setPoints(final Set<Location> points)
    {
        this.points.addAll(
                points.parallelStream().map(point -> new PointInformation(point, Optional.empty()))
                        .collect(Collectors.toSet()));
    }

    public void setProjectName(final String projectName)
    {
        this.projectName = projectName;
    }

    public void setTaskIdentifier(final String taskIdentifier)
    {
        this.taskIdentifier = taskIdentifier;
    }

    protected JsonArray generateTaskFeatures(final Set<PointInformation> source,
            final Optional<JsonArray> geoJson)
    {
        final JsonArray features = new JsonArray();
        if (source.isEmpty() && !geoJson.isPresent())
        {
            throw new CoreException("Could not find any features for the task [{}].",
                    this.toString());
        }
        source.forEach(point ->
        {
            final JsonObject feature = new JsonObject();
            final JsonObject geometry = new JsonObject();
            final JsonArray coordinates = new JsonArray();
            coordinates.add(new JsonPrimitive(point.getLocation().getLongitude().asDegrees()));
            coordinates.add(new JsonPrimitive(point.getLocation().getLatitude().asDegrees()));
            geometry.add(TASK_TYPE, new JsonPrimitive(POINT));
            geometry.add(TASK_FEATURE_COORDINATES, coordinates);
            feature.add(TASK_FEATURE_GEOMETRY, geometry);
            feature.add(TASK_TYPE, new JsonPrimitive(FEATURE));
            final JsonObject pointInformation = new JsonObject();
            point.getDescription().ifPresent(description -> pointInformation.add(KEY_DESCRIPTION,
                    new JsonPrimitive(description)));
            feature.add(TASK_FEATURE_PROPERTIES, pointInformation);
            features.add(feature);
        });

        geoJson.ifPresent(json ->
        {
            final JsonObject propertiesField = (JsonObject) json.get(0).getAsJsonObject()
                    .get(TASK_FEATURE_PROPERTIES);
            propertiesField.add(CHECK_GENERATOR, new JsonPrimitive(this.challengeName));
            propertiesField.add(FRAMEWORK_GENERATOR, new JsonPrimitive(FRAMEWORK));
            json.forEach(features::add);
        });

        return features;
    }
}
