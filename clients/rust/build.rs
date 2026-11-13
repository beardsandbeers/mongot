fn main() -> Result<(), Box<dyn std::error::Error>> {
    std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path()?);
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR")?);
    let source = "proto/standalone.proto";
    let canonical = std::path::Path::new("../../src/proto/standalone/v1/standalone.proto");
    if canonical.exists() && std::fs::read(canonical)? != std::fs::read(source)? {
        return Err("Packaged API is stale: run python3 clients/sync_proto.py".into());
    }
    println!("cargo:rerun-if-changed={}", canonical.display());
    println!("cargo:rerun-if-changed={source}");
    tonic_build::configure()
        .build_server(false)
        .file_descriptor_set_path(out.join("descriptor.bin"))
        .compile_protos(&[source], &["proto"])?;
    let descriptor = std::fs::read(out.join("descriptor.bin"))?;
    pbjson_build::Builder::new()
        .register_descriptors(&descriptor)?
        .build(&[".mongot.standalone.v1"])?;
    Ok(())
}
