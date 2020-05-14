package serviceImpl;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.apache.http.HttpHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import utils.DBHelper;

/**
 * @author Ye
 * @time 2019��3��29��
 *
 *       ��˵����ͨ��BulkProcess������Mysql���ݵ���ElasticSearch��
 */

public class BulkProcessImpl {
	
	private static final Logger logger = LogManager.getLogger(BulkProcessImpl.class);

	public static void main(String[] args) {
		try {
//			test();
			long startTime = System.currentTimeMillis();
			String tableName = "user";
//			String tableName = "TBL_ORG";
			createIndex(tableName);
			BulkProcessImpl bulk = new BulkProcessImpl();
			bulk.writeMysqlDataToES(tableName);
			//�������Լ��ʼǱ����Ե�Eclipse�����������ݣ� 1800000 ,use time: 186s,5���̣߳�5000һ�����Σ�δ������ˢ��ʱ���븱����
			//���������ݣ� 1800000 ,use time: 168s,5���̣߳�5000һ�����Σ�������ˢ��ʱ�䣨-1���븱������0��

			logger.info(" use time: " + (System.currentTimeMillis() - startTime) / 1000 + "s");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void createIndex(String indexName) throws IOException {
		RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")));// ��ʼ��
		CreateIndexRequest requestIndex = new CreateIndexRequest(indexName.toLowerCase());// ��������
		// ������ÿ����������������֮�������ض����á����ø�������ˢ��ʱ�������������Ч���в�С������
		requestIndex.settings(Settings.builder().put("index.number_of_shards", 5)
				.put("index.number_of_replicas", 0)
				.put("index.refresh_interval", "-1"));
		CreateIndexResponse createIndexResponse = client.indices().create(requestIndex, RequestOptions.DEFAULT);
		logger.info("isAcknowledged:" + createIndexResponse.isAcknowledged());
		logger.info("isShardsAcknowledged:" + createIndexResponse.isShardsAcknowledged());

		client.close();

	}

	/**
	 * ��mysql ���ݲ����װ��es��Ҫ��map��ʽ��ͨ������д��es��
	 * 
	 * @param tableName
	 */
	public void writeMysqlDataToES(String tableName) {

//		RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(new HttpHost("nn01", 9200, "http")));// ��ʼ��
		RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")));// ��ʼ��
		BulkProcessor bulkProcessor = getBulkProcessor(client);

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = DBHelper.getConn();
			logger.info("Start handle data :" + tableName);

			String sql = "SELECT * from " + tableName;

			ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			ps.setFetchSize(Integer.MIN_VALUE);
			rs = ps.executeQuery();

			ResultSetMetaData colData = rs.getMetaData();

			ArrayList<HashMap<String, String>> dataList = new ArrayList<HashMap<String, String>>();

			HashMap<String, String> map = null;
			int count = 0;
			String c = null;
			String v = null;
			while (rs.next()) {
				count++;
				map = new HashMap<String, String>(100);
				for (int i = 1; i <= colData.getColumnCount(); i++) {
					c = colData.getColumnName(i);
					v = rs.getString(c);
					map.put(c, v);
				}
				dataList.add(map);
				System.out.println(dataList);
				// ÿ20����дһ�Σ���������ε������һ���ύ
				//��Ϊÿ2��дһ��
				if (count % 2 == 0) {
					logger.info("Mysql handle data number : " + count);
					// д��ES
					for (HashMap<String, String> hashMap2 : dataList) {
						bulkProcessor.add(new IndexRequest(tableName.toLowerCase(), "gzdc", hashMap2.get("S_GUID"))
								.source(hashMap2));
					}
					// ÿ�ύһ�α㽫map��list���
					map.clear();
					dataList.clear();
				}
			}

			// count % 200000 ����δ�ύ������
			for (HashMap<String, String> hashMap2 : dataList) {
				bulkProcessor.add(
						new IndexRequest(tableName.toLowerCase(), "gzdc", hashMap2.get("S_GUID")).source(hashMap2));
			}

			logger.info("-------------------------- Finally insert number total : " + count);
            // ������ˢ�µ�es, ע����һ��ִ�к󲢲���������Ч��ȡ����bulkProcessor���õ�ˢ��ʱ��
			bulkProcessor.flush();
			
		} catch (Exception e) {
			logger.error(e.getMessage());
		} finally {
			try {
				rs.close();
				ps.close();
				conn.close();
				boolean terminatedFlag = bulkProcessor.awaitClose(150L, TimeUnit.SECONDS);
				client.close();
				logger.info(terminatedFlag);
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
		}
	}

	private static BulkProcessor getBulkProcessor(RestHighLevelClient client) {

		BulkProcessor bulkProcessor = null;
		try {

			BulkProcessor.Listener listener = new BulkProcessor.Listener() {
				@Override
				public void beforeBulk(long executionId, BulkRequest request) {
					logger.info("Try to insert data number : " + request.numberOfActions());
				}

				@Override
				public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
					logger.info("************** Success insert data number : " + request.numberOfActions() + " , id: "
							+ executionId);
				}

				@Override
				public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
					logger.error("Bulk is unsuccess : " + failure + ", executionId: " + executionId);
				}
			};

			BiConsumer<BulkRequest, ActionListener<BulkResponse>> bulkConsumer = (request, bulkListener) -> client
					.bulkAsync(request, RequestOptions.DEFAULT, bulkListener);

			BulkProcessor.Builder builder = BulkProcessor.builder(bulkConsumer, listener);
			builder.setBulkActions(2);
			builder.setBulkSize(new ByteSizeValue(300L, ByteSizeUnit.MB));
			builder.setConcurrentRequests(10);
			builder.setFlushInterval(TimeValue.timeValueSeconds(100L));
			builder.setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(1L), 3));
			// ע��㣺������о��е�ӣ�����������û����һ������������һʱ����Ҳûע�⣬�ڵ���ʱע�⿴�ŷ��֣������builder���õ�����û����Ч
			bulkProcessor = builder.build();

		} catch (Exception e) {
			e.printStackTrace();
			try {
				bulkProcessor.awaitClose(100L, TimeUnit.SECONDS);
				client.close();
			} catch (Exception e1) {
				logger.error(e1.getMessage());
			}

		}
		return bulkProcessor;
	}
}
