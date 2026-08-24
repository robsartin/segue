package com.robsartin.segue.jena;

import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.GraphStoreContract;

class JenaGraphStoreContractTest extends GraphStoreContract {

  @Override
  protected GraphStore createStore() {
    return new JenaGraphStore();
  }
}
