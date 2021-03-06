package ApacheBeam;

import com.google.gson.Gson;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mypipeline {
    private static final Logger LOG = LoggerFactory.getLogger(Mypipeline.class);

    public interface Options extends DataflowPipelineOptions {
        @Description("Input topic name")
        String getInputTopic();
        void setInputTopic(String inputTopic);

        @Description("BigQuery table name")
        String getTableName();
        void setTableName(String tableName);
    }

    public static void main(String[] args) {
        Options options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(Options.class);

        run(options);
    }
    static class JsonToCommonLog extends DoFn<String, CommonLog>  {

        @ProcessElement
        public void processElement(@Element String json, OutputReceiver<CommonLog> r) throws Exception {
            Gson gson = new Gson();
            CommonLog commonLog = gson.fromJson(json, CommonLog.class);
            r.output(commonLog);
        }
    }
    public static final Schema rawSchema = Schema
            .builder()
            .addInt64Field("id")
            .addStringField("name")
            .addStringField("surname")
            .build();

    public static PipelineResult run(Options options) {

        Pipeline pipeline = Pipeline.create(options);
//        options.setJobName("Mypipeline" + System.currentTimeMillis());
//        options.setProject("my-project-id");
//        options.setStagingLocation("gs://my-bucket/c4e-uc1-dataflow-temp-6");
        options.setRunner(DataflowRunner.class);

        PCollection<CommonLog> commonLogs = pipeline
                .apply("ReadMessage", PubsubIO.readStrings()
                        .fromTopic(options.getInputTopic()))
                .apply("ParseJson", ParDo.of(new JsonToCommonLog()));
        commonLogs
                .apply("SelectFields", Select.fieldNames("id", "name","surname"))
                    //.apply("AddProcessingTimeField", AddFields.<Row>create().field("processing_timestamp", Schema.FieldType.DATETIME))
                .apply("AddProcessingcontent", MapElements.via(new SimpleFunction<Row, Row>() {
                                                                @Override
                                                                public Row apply(Row row) {
                                                                    return Row.withSchema(rawSchema)
                                                                            .addValues(
                                                                                    row.getInt64("id"),
                                                                                    row.getString("name"),
                                                                                    row.getString("surname")
                                                                            .build();
                                                                }
                                                            }
                )).setRowSchema(rawSchema);
        commonLogs.apply("WriteToBQ",
                        BigQueryIO.<CommonLog>write().to(options.getTableName()).useBeamSchema()
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

        LOG.info("Building pipeline...");

        return pipeline.run();

    }
}
