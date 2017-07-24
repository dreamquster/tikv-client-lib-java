package com.pingcap.tikv;

import com.google.common.collect.ImmutableList;
import com.pingcap.tikv.catalog.Catalog;
import com.pingcap.tikv.expression.TiColumnRef;
import com.pingcap.tikv.expression.TiConstant;
import com.pingcap.tikv.expression.TiExpr;
import com.pingcap.tikv.expression.scalar.Equal;
import com.pingcap.tikv.expression.scalar.NotEqual;
import com.pingcap.tikv.meta.TiDBInfo;
import com.pingcap.tikv.meta.TiIndexInfo;
import com.pingcap.tikv.meta.TiSelectRequest;
import com.pingcap.tikv.meta.TiTableInfo;
import com.pingcap.tikv.operation.SchemaInfer;
import com.pingcap.tikv.predicates.PredicateUtils;
import com.pingcap.tikv.predicates.ScanBuilder;
import com.pingcap.tikv.region.TiRegion;
import com.pingcap.tikv.row.Row;
import com.pingcap.tikv.util.RangeSplitter;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

  private static TiConfiguration conf =
      TiConfiguration.createDefault(ImmutableList.of("127.0.0.1:" + 2379));
  private static TiCluster cluster = TiCluster.getCluster(conf);
  private static Snapshot snapshot = cluster.createSnapshot();

  public static void main(String[] args) throws Exception {
    // May need to save this reference
    Logger log = Logger.getLogger("io.grpc");
    log.setLevel(Level.WARNING);

    Catalog cat = cluster.getCatalog();
    TiDBInfo db = cat.getDatabase("tpch");
    TiTableInfo table = cat.getTable(db, "customer");

    TiIndexInfo index = table.getIndices().get(0);
//    TiIndexInfo index = TiIndexInfo.generateFakePrimaryKeyIndex(table);
    List<TiExpr> exprs =
        ImmutableList.of(
            new Equal(TiColumnRef.create("c_name", table), TiConstant.create("Customer#000000001"))
//            new NotEqual(TiColumnRef.create("c_address", table), TiConstant.create("test"))
        );

    ScanBuilder scanBuilder = new ScanBuilder();
    ScanBuilder.ScanPlan scanPlan = scanBuilder.buildScan(exprs, index, table);

    TiSelectRequest selReq = new TiSelectRequest();
    selReq
        .addRanges(scanPlan.getKeyRanges())
        .setTableInfo(table)
        .setIndexInfo(index)
        .addRequiredColumn(TiColumnRef.create("c_name", table))
//        .addRequiredColumn(TiColumnRef.create("c_address", table))
        .setStartTs(snapshot.getVersion());

    if (conf.isIgnoreTruncate()) {
      selReq.setTruncateMode(TiSelectRequest.TruncateMode.IgnoreTruncation);
    } else if (conf.isTruncateAsWarning()) {
      selReq.setTruncateMode(TiSelectRequest.TruncateMode.TruncationAsWarning);
    }

//    selReq.addWhere(PredicateUtils.mergeCNFExpressions(scanPlan.getFilters()));
    selReq.addWhere(exprs.get(0));
    List<RangeSplitter.RegionTask> keyWithRegionTasks =
        RangeSplitter.newSplitter(cluster.getRegionManager())
            .splitRangeByRegion(selReq.getRanges());
    for (RangeSplitter.RegionTask task : keyWithRegionTasks) {
//      Iterator<Row> it = snapshot.select(selReq, task);
      Iterator<Row> it = snapshot.selectByIndex(selReq, task, true);

      while (it.hasNext()) {
        Row r = it.next();
        SchemaInfer schemaInfer = SchemaInfer.create(selReq);
        for (int i = 0; i < r.fieldCount(); i++) {
          Object val = r.get(i, schemaInfer.getType(i));
          System.out.print(val);
          System.out.print(" ");
        }
        System.out.print("\n");
      }
    }
    System.out.println("done");
  }
}
