use mongot_standalone_client::{Client, ClientOptions};
use serde_json::{Value, json};
use std::{
    io::{self, BufRead, Write},
    time::Duration,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut client = Client::connect(ClientOptions {
        address: std::env::var("MONGOT_ADDRESS").unwrap_or_else(|_| "127.0.0.1:27029".into()),
        ca_file: std::env::var_os("MONGOT_CA").map(Into::into),
        token_file: std::env::var_os("MONGOT_TOKEN_FILE").map(Into::into),
        timeout: Duration::from_secs_f64(
            std::env::var("MONGOT_TIMEOUT")
                .unwrap_or_else(|_| "30".into())
                .parse()?,
        ),
    })
    .await?;
    for line in io::stdin().lock().lines() {
        let result = dispatch(&mut client, &line?).await;
        println!(
            "{}",
            result.unwrap_or_else(
                |e| json!({"error":{"code":"CLIENT_ERROR","message":e.to_string()}})
            )
        );
        io::stdout().flush()?;
    }
    Ok(())
}
async fn dispatch(client: &mut Client, line: &str) -> Result<Value, Box<dyn std::error::Error>> {
    let call: Value = serde_json::from_str(line)?;
    let request = call.get("request").cloned().unwrap_or_else(|| json!({}));
    macro_rules! invoke { ($method:ident) => {
        match client.$method(serde_json::from_value(request)?).await {
            Ok(response) => Ok(json!({"response":response})),
            Err(error) => Ok(json!({"error":{"code":code_name(error.code()),"message":error.message()}})),
        }
    }; }
    match call["method"].as_str().unwrap_or("") {
        "CreateCollection" => invoke!(create_collection),
        "GetCollection" => invoke!(get_collection),
        "CreateIndex" => invoke!(create_index),
        "UpdateIndex" => invoke!(update_index),
        "DropIndex" => invoke!(drop_index),
        "GetIndex" => invoke!(get_index),
        "ListIndexes" => invoke!(list_indexes),
        "Write" => invoke!(write),
        "WriteBatch" => invoke!(write_batch),
        "GetWriteResult" => invoke!(get_write_result),
        "AwaitSequence" => invoke!(await_sequence),
        "GetStatus" => invoke!(get_status),
        "Search" => invoke!(search),
        "VectorSearch" => invoke!(vector_search),
        "GetMore" => invoke!(get_more),
        "KillCursor" => invoke!(kill_cursor),
        "Upsert" => invoke!(upsert),
        "Delete" => invoke!(delete),
        "DeleteByQuery" => invoke!(delete_by_query),
        "GetDeleteByQueryResult" => invoke!(get_delete_by_query_result),
        _ => Err("Unknown method".into()),
    }
}
fn code_name(code: tonic::Code) -> &'static str {
    use tonic::Code::*;
    match code {
        Ok => "OK",
        Cancelled => "CANCELLED",
        Unknown => "UNKNOWN",
        InvalidArgument => "INVALID_ARGUMENT",
        DeadlineExceeded => "DEADLINE_EXCEEDED",
        NotFound => "NOT_FOUND",
        AlreadyExists => "ALREADY_EXISTS",
        PermissionDenied => "PERMISSION_DENIED",
        ResourceExhausted => "RESOURCE_EXHAUSTED",
        FailedPrecondition => "FAILED_PRECONDITION",
        Aborted => "ABORTED",
        OutOfRange => "OUT_OF_RANGE",
        Unimplemented => "UNIMPLEMENTED",
        Internal => "INTERNAL",
        Unavailable => "UNAVAILABLE",
        DataLoss => "DATA_LOSS",
        Unauthenticated => "UNAUTHENTICATED",
    }
}
