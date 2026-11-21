// WatchLater+ web: /.netlify/functions/inbox
//   ?k=<your code>&add=<link>   keep a shared link until the app opens (used by the iPhone Shortcut)
//   ?k=<your code>&list=1       the links that are waiting
//   ?k=<your code>&done=<time>  remove the ones the app has taken
import { getStore } from '@netlify/blobs';
import { handle } from '../shared/inbox-core.mjs';

export default async (req) => {
  try {
    return await handle(req, getStore({ name: 'wl-inbox', consistency: 'strong' }));
  } catch (e) {
    console.error('inbox failed', e);
    return new Response('WatchLater+ could not save this right now. Try again.', { status: 500 });
  }
};
