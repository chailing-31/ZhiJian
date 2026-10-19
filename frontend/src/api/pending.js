export class FeatureNotReadyError extends Error {
  constructor(feature) { super(`${feature}尚未接入，未发送请求、未保存数据。`); this.name = 'FeatureNotReadyError' }
}
export async function notConnected(feature) { throw new FeatureNotReadyError(feature) }
