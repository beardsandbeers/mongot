//! All standalone RPCs with BSON helpers, TLS/bearer authentication and a fresh deadline per call.
pub mod api {
    tonic::include_proto!("mongot.standalone.v1");
    include!(concat!(env!("OUT_DIR"), "/mongot.standalone.v1.serde.rs"));
}
use api::*;
use std::{path::PathBuf, time::Duration};
use tonic::{
    Request, Status,
    service::Interceptor,
    transport::{Certificate, Channel, ClientTlsConfig, Endpoint},
};

pub use bson;
pub fn encode_bson(document: &bson::Document) -> bson::ser::Result<Vec<u8>> {
    bson::to_vec(document)
}
pub fn decode_bson(bytes: &[u8]) -> bson::de::Result<bson::Document> {
    bson::from_slice(bytes)
}

pub struct ClientOptions {
    pub address: String,
    pub ca_file: Option<PathBuf>,
    pub token_file: Option<PathBuf>,
    pub timeout: Duration,
}
impl Default for ClientOptions {
    fn default() -> Self {
        Self {
            address: "127.0.0.1:27029".into(),
            ca_file: None,
            token_file: None,
            timeout: Duration::from_secs(30),
        }
    }
}
#[derive(Clone)]
pub struct BearerAuth(Option<tonic::metadata::MetadataValue<tonic::metadata::Ascii>>);
impl Interceptor for BearerAuth {
    fn call(&mut self, mut request: Request<()>) -> Result<Request<()>, Status> {
        if let Some(token) = &self.0 {
            request
                .metadata_mut()
                .insert("authorization", token.clone());
        }
        Ok(request)
    }
}
type Connection = tonic::service::interceptor::InterceptedService<Channel, BearerAuth>;
pub struct Client {
    timeout: Duration,
    pub catalog: catalog_service_client::CatalogServiceClient<Connection>,
    pub documents: document_service_client::DocumentServiceClient<Connection>,
    pub operations: operations_service_client::OperationsServiceClient<Connection>,
    pub queries: query_service_client::QueryServiceClient<Connection>,
}
impl Client {
    pub async fn connect(options: ClientOptions) -> Result<Self, Box<dyn std::error::Error>> {
        if options.timeout.is_zero() {
            return Err("timeout must be positive".into());
        }
        if options.token_file.is_some() && options.ca_file.is_none() {
            return Err("bearer authentication requires TLS".into());
        }
        let scheme = if options.ca_file.is_some() {
            "https"
        } else {
            "http"
        };
        let mut endpoint = Endpoint::from_shared(format!("{scheme}://{}", options.address))?
            .connect_timeout(options.timeout);
        if let Some(path) = options.ca_file {
            endpoint = endpoint.tls_config(
                ClientTlsConfig::new().ca_certificate(Certificate::from_pem(std::fs::read(path)?)),
            )?;
        }
        let auth = BearerAuth(
            options
                .token_file
                .map(|path| -> Result<_, Box<dyn std::error::Error>> {
                    Ok(format!("Bearer {}", std::fs::read_to_string(path)?.trim()).parse()?)
                })
                .transpose()?,
        );
        let channel = endpoint.connect().await?;
        Ok(Self {
            timeout: options.timeout,
            catalog: catalog_service_client::CatalogServiceClient::with_interceptor(
                channel.clone(),
                auth.clone(),
            )
            .max_decoding_message_size(64 * 1024 * 1024),
            documents: document_service_client::DocumentServiceClient::with_interceptor(
                channel.clone(),
                auth.clone(),
            )
            .max_decoding_message_size(64 * 1024 * 1024),
            operations: operations_service_client::OperationsServiceClient::with_interceptor(
                channel.clone(),
                auth.clone(),
            )
            .max_decoding_message_size(64 * 1024 * 1024),
            queries: query_service_client::QueryServiceClient::with_interceptor(
                channel.clone(),
                auth.clone(),
            )
            .max_decoding_message_size(64 * 1024 * 1024),
        })
    }
    pub async fn create_index(
        &mut self,
        message: CreateIndexRequest,
    ) -> Result<IndexDescription, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .catalog
            .create_index(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn update_index(
        &mut self,
        message: UpdateIndexRequest,
    ) -> Result<IndexDescription, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .catalog
            .update_index(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn drop_index(
        &mut self,
        message: DropIndexRequest,
    ) -> Result<IndexDescription, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .catalog
            .drop_index(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn get_index(
        &mut self,
        message: GetIndexRequest,
    ) -> Result<IndexDescription, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .catalog
            .get_index(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn list_indexes(
        &mut self,
        message: ListIndexesRequest,
    ) -> Result<ListIndexesResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .catalog
            .list_indexes(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn get_status(&mut self, message: GetStatusRequest) -> Result<ServiceStatus, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .operations
            .get_status(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn search(&mut self, message: QueryRequest) -> Result<QueryResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .queries
            .search(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn vector_search(&mut self, message: QueryRequest) -> Result<QueryResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .queries
            .vector_search(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn get_more(&mut self, message: GetMoreRequest) -> Result<QueryResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .queries
            .get_more(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn kill_cursor(
        &mut self,
        message: KillCursorRequest,
    ) -> Result<KillCursorResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .queries
            .kill_cursor(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn upsert(&mut self, message: UpsertRequest) -> Result<UpsertResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .documents
            .upsert(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
    pub async fn delete(&mut self, message: DeleteRequest) -> Result<DeleteResponse, Status> {
        let mut request = Request::new(message);
        request.set_timeout(self.timeout);
        Ok(self
            .documents
            .delete(request)
            .await
            .map_err(normalize_status)?
            .into_inner())
    }
}

// Tonic 0.12 reports its local transport timer as CANCELLED. Distinguish that typed
// source from a server cancellation and expose the standard deadline status.
fn normalize_status(status: Status) -> Status {
    if status.code() == tonic::Code::Cancelled {
        let mut source = std::error::Error::source(&status);
        while let Some(error) = source {
            if error.is::<tonic::TimeoutExpired>() {
                let mut deadline = Status::with_details_and_metadata(
                    tonic::Code::DeadlineExceeded,
                    status.message().to_owned(),
                    status.details().to_vec().into(),
                    status.metadata().clone(),
                );
                deadline.set_source(std::sync::Arc::new(status));
                return deadline;
            }
            source = error.source();
        }
    }
    status
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_timeout_maps_to_deadline_exceeded() {
        let status = Status::from_error(Box::new(tonic::TimeoutExpired(())));
        assert_eq!(
            normalize_status(status).code(),
            tonic::Code::DeadlineExceeded
        );
    }

    #[test]
    fn server_cancellation_is_preserved() {
        let status = Status::cancelled("Timeout expired");
        assert_eq!(normalize_status(status).code(), tonic::Code::Cancelled);
    }
}
